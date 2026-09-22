package com.city.service.recommend;

import com.city.agent.factory.AgentFactory;
import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityResponse;
import com.city.model.RecommendResult;
import com.city.model.RecommendedActivityOption;
import com.city.model.ResponseResult;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.service.trace.AgentTraceService;
import com.city.util.LlmJsonService;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐结果表达 Agent。
 *
 * <p>它位于“Java 已完成检索/排序”之后，不负责决定候选池，也不允许创建候选之外的新活动；
 * 主要工作是给已排序候选生成具体推荐理由，并把结构化结果组织成用户可直接阅读的 speechText。</p>
 *
 * <p>因此职责边界是：Java 决定“有哪些活动、顺序是什么、事实是什么”，LLM 决定“怎么解释这些结果”。
 * 即使 LLM 失败，也会用确定性模板返回同一批候选，推荐主链路不会因为生成层异常而丢失结果。</p>
 */
@Service
public class RecommendResponseAgentService {

    private final AgentFactory agentFactory;
    private final LlmJsonService llmJsonService;
    private final AgentTraceService agentTraceService;
    private final String modelName;

    public RecommendResponseAgentService(
            AgentFactory agentFactory,
            LlmJsonService llmJsonService,
            AgentTraceService agentTraceService,
            @Value("${city.llm.response-model:qwen-turbo}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    /** 无天气上下文时的便捷入口。 */
    public Result recommendAndRespond(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle slots,
            List<ActivityItem> rankedMeals) {
        return recommendAndRespond(sessionId, userInput, sourceMode, slots, rankedMeals, WeatherRecommendationContext.inactive());
    }

    /**
     * 为 Java 排好序的候选生成最终推荐说明。
     *
     * <p>这里只取前 3 个候选，并始终保持输入顺序。Agent 只能为这 3 个活动生成 reason/speechText，
     * parseOptions 会再次按 activityId 白名单过滤模型输出，防止幻觉活动进入响应。</p>
     */
    public Result recommendAndRespond(
            String sessionId, String userInput, SourceMode sourceMode, SlotBundle slots,
            List<ActivityItem> rankedMeals, WeatherRecommendationContext weather) {
        List<ActivityItem> topMeals = rankedMeals == null ? List.of() : rankedMeals.stream().limit(3).toList();
        if (topMeals.isEmpty()) {
            RecommendResult empty = RecommendResult.empty();
            return new Result(empty, ResponseResult.textOnly("暂时没有找到很匹配的活动，可以试试补充时间、氛围或活动类型"));
        }

        boolean needDisclaimer = needsDisclaimer(slots);
        try {
            ReActAgent agent = agentFactory.get(sessionId).recommendResponse();
            // 所需上下文完全由本轮 Prompt 显式提供，避免 Agent memory 里残留旧候选造成串结果。
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "RecommendResponseAgent",
                    modelName,
                    agent,
                    buildUserPrompt(userInput, sourceMode, slots, topMeals, weather)
            );
            ParsedOutput parsed = parseOutput(response.getTextContent(), topMeals, slots);
            RecommendResult recommend = new RecommendResult(parsed.options(), needDisclaimer);
            ResponseResult responseResult = new ResponseResult(
                    parsed.speechText(), toDisplayBlocks(recommend, topMeals), "WAIT_USER");
            return new Result(recommend, responseResult);
        } catch (Exception ignored) {
            // 生成层失败时保留同一批 top3，只把理由和文本降级为 Java 模板。
            RecommendResult recommend = new RecommendResult(templateOptions(topMeals, slots), needDisclaimer);
            return new Result(
                    recommend,
                    new ResponseResult(templateSpeech(recommend), toDisplayBlocks(recommend, topMeals), "WAIT_USER")
            );
        }
    }

    /**
     * 构造“受约束生成”Prompt：候选活动是唯一事实源，模型不得改变候选集合或排序。
     */
    private String buildUserPrompt(String userInput, SourceMode sourceMode, SlotBundle slots, List<ActivityItem> topMeals,
                                   WeatherRecommendationContext weather) {
        return """
                用户原话：%s
                数据源模式：%s
                本轮槽位：%s
                候选活动：%s
                天气排序说明：%s

                请只输出一个合法 JSON 对象，包含：
                - recommendations：数组，每项包含 activityId 和 reason。
                - speechText：可以直接展示给用户的完整推荐回答。

                ## 强制要求
                - 只能推荐“候选活动”中提供的活动，禁止编造候选之外的活动、时间、地点、价格或其他事实。
                - recommendations 中的每个 activityId 必须来自候选活动；每个 reason 必须说明该活动为什么适合当前用户需求。
                - speechText 不能只是“推荐以下活动：”“可以考虑这些：”之类的开场句，必须是一段完整可直接发送给用户的推荐内容。
                - speechText 必须明确写出每一个最终推荐活动的活动名称，并给出对应的具体推荐理由；不能只把理由放在 recommendations.reason 里而不写进 speechText。
                - speechText 中的活动及理由必须与 recommendations 一一对应，内容保持一致，不要遗漏任何最终推荐项。
                - 推荐顺序必须严格按照输入的候选活动顺序，不要自行重新排序。
                - 如果候选有 3 个，speechText 应完整覆盖这 3 个；如果少于 3 个，则覆盖全部候选。
                - 推荐理由应结合候选活动已有的槽位、时间和当前用户需求，简洁具体，不要泛泛而谈。

                ## 推荐输出风格
                speechText 建议使用“简短开场 + 编号推荐”的形式，例如：
                “根据你的需求，我更推荐这几个：
                1. 活动A：推荐理由。
                2. 活动B：推荐理由。
                3. 活动C：推荐理由。”

                最终仍只输出 JSON，不要输出 Markdown 代码块，不要补充 JSON 之外的文字。
                """.formatted(userInput, sourceMode, slots, topMeals,
                weather != null && weather.active() ? weather.summary() : "未启用天气排序");
    }

    /**
     * 解析模型输出。recommendations 负责结构化理由，speechText 为空时用模板从结构化结果重新生成。
     */
    private ParsedOutput parseOutput(String content, List<ActivityItem> topMeals, SlotBundle slots) {
        JsonNode root = llmJsonService.parseObject(content);
        List<RecommendedActivityOption> options = parseOptions(root.path("recommendations"), topMeals, slots);
        String speechText = root.path("speechText").asText("").trim();
        if (speechText.isBlank()) {
            speechText = templateSpeech(new RecommendResult(options, needsDisclaimer(slots)));
        }
        return new ParsedOutput(options, speechText);
    }

    /**
     * 用 Java 候选 ID 白名单重建最终推荐列表。
     *
     * <p>模型漏写理由时使用 templateReason；模型输出未知 activityId 时直接忽略；最终顺序永远按 topMeals，
     * 所以 Agent 不能通过 JSON 改变排序结果。</p>
     */
    private List<RecommendedActivityOption> parseOptions(JsonNode recommendationsNode, List<ActivityItem> topMeals, SlotBundle slots) {
        Map<Long, ActivityItem> byId = new LinkedHashMap<>();
        topMeals.forEach(activity -> byId.put(activity.id(), activity));
        Map<Long, String> reasons = new LinkedHashMap<>();
        if (recommendationsNode.isArray()) {
            recommendationsNode.forEach(node -> {
                long activityId = node.path("activityId").isMissingNode()
                        ? node.path("itemId").asLong()
                        : node.path("activityId").asLong();
                String reason = node.path("reason").asText("");
                if (byId.containsKey(activityId) && !reason.isBlank()) {
                    reasons.put(activityId, reason);
                }
            });
        }
        List<RecommendedActivityOption> result = new ArrayList<>();
        for (ActivityItem activity : topMeals) {
            result.add(toOption(activity, reasons.getOrDefault(activity.id(), templateReason(activity, slots))));
        }
        return result;
    }

    /** LLM 不可用时，为全部候选生成确定性推荐理由。 */
    private List<RecommendedActivityOption> templateOptions(List<ActivityItem> topMeals, SlotBundle slots) {
        return topMeals.stream()
                .map(activity -> toOption(activity, templateReason(activity, slots)))
                .toList();
    }

    /** 把原始 ActivityItem 与生成理由组合成对外推荐选项；活动事实仍来自 ActivityItem。 */
    private RecommendedActivityOption toOption(ActivityItem activity, String reason) {
        return new RecommendedActivityOption(
                activity.id(), activity.sourceType(), activity.name(), reason, activity.matchScore(), activity.slots());
    }

    /**
     * 最小模板理由：只引用当前已有槽位和活动名称，不生成数据库中不存在的事实。
     */
    private String templateReason(ActivityItem activity, SlotBundle slots) {
        if (slots != null && !slots.budget().isEmpty()) {
            return activity.name() + "比较符合你提到的" + String.join("、", slots.budget()) + "诉求。";
        }
        if (slots != null && !slots.style().isEmpty()) {
            return activity.name() + "比较贴近你想要的" + String.join("、", slots.style()) + "活动风格。";
        }
        return activity.name() + "和你这轮表达的活动偏好匹配度较高。";
    }

    /**
     * 前端活动卡片必须以原始 ActivityItem 为事实来源；RecommendResult 只决定展示哪些活动。
     * 这一步再次按 ID 回表式映射，可避免 LLM 生成内容污染地点、时间等卡片字段。
     */
    private List<ActivityResponse> toDisplayBlocks(RecommendResult recommendResult, List<ActivityItem> candidates) {
        if (recommendResult == null || recommendResult.recommendations() == null
                || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, ActivityItem> byId = new LinkedHashMap<>();
        for (ActivityItem activity : candidates) {
            if (activity != null && activity.id() != null) {
                byId.putIfAbsent(activity.id(), activity);
            }
        }
        return recommendResult.recommendations().stream()
                .map(option -> option == null ? null : byId.get(option.itemId()))
                .filter(activity -> activity != null)
                .map(ActivityResponse::from)
                .toList();
    }

    /** 把结构化推荐项拼成可直接展示的 fallback 文本。 */
    private String templateSpeech(RecommendResult recommendResult) {
        if (recommendResult == null || recommendResult.recommendations().isEmpty()) {
            return "暂时没有找到很匹配的活动，你可以补充时间段、活动类型或氛围偏好。";
        }
        StringBuilder builder = new StringBuilder("我优先给你推荐这几款：");
        for (RecommendedActivityOption option : recommendResult.recommendations()) {
            builder.append("\n- ").append(option.name()).append("：").append(option.reason());
        }
        if (recommendResult.needDisclaimer()) {
            builder.append("\n这些建议仅供周末娱乐参考，具体安排请根据实际情况调整。");
        }
        return builder.toString();
    }

    /**
     * 兼容历史标签的免责声明判断。这里只影响响应文案，不影响候选检索和排序。
     */
    private boolean needsDisclaimer(SlotBundle slots) {
        return slots != null && slots.budget().stream().anyMatch(value ->
                value.contains("减脂") || value.contains("低糖") || value.contains("控碳水") || value.contains("养胃"));
    }

    /** 同时返回结构化推荐结果和最终前端响应。 */
    public record Result(RecommendResult recommend, ResponseResult response) {
    }

    /** LLM 单次调用的内部解析结果。 */
    private record ParsedOutput(List<RecommendedActivityOption> options, String speechText) {
    }
}

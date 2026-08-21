package com.city.service.recommend;

import com.city.agent.factory.AgentFactory;
import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityResponse;
import com.city.model.RecommendResult;
import com.city.model.RecommendedActivityOption;
import com.city.model.ResponseResult;
import com.city.model.SlotBundle;
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
 * RecommendResponseAgent 服务（Orchestrator 推荐流水线第三层）。
 * 一次 LLM 调用同时生成 top3 推荐理由和面向用户的口语 speechText。
 */
@Service
public class RecommendResponseAgentService {

    /**
     * 按 sessionId 提供 RecommendResponseAgent 实例的工厂。
     */
    private final AgentFactory agentFactory;

    /**
     * 从 LLM 输出文本中提取 JSON 对象的工具。
     */
    private final LlmJsonService llmJsonService;

    /**
     * 链路追踪服务，callAgent 内部记录 AGENT_CALL 事件。
     */
    private final AgentTraceService agentTraceService;

    /**
     * RecommendResponseAgent 使用的主模型名，来自配置 diet.llm.main-model。
     */
    private final String modelName;

    /**
     * 构造器注入依赖。
     */
    public RecommendResponseAgentService(
            AgentFactory agentFactory,
            LlmJsonService llmJsonService,
            AgentTraceService agentTraceService,
            @Value("${diet.llm.main-model:qwen-max}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    /**
     * 合并推荐链路输出：RecommendResult + ResponseResult。
     * 由 Orchestrator#completeRecommendation 在 ACTIVITY_RANKED 之后调用。
     */
    public Result recommendAndRespond(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle slots,
            List<ActivityItem> rankedMeals) {
        // 取重排结果 top3 作为 LLM 输入候选（不允许编造候选之外的餐食）
        List<ActivityItem> topMeals = rankedMeals == null ? List.of() : rankedMeals.stream().limit(3).toList();

        // top3 为空时直接返回空推荐 + 提示文案，不调用 LLM
        if (topMeals.isEmpty()) {
            RecommendResult empty = RecommendResult.empty();
            return new Result(empty, ResponseResult.textOnly("暂时没有找到很匹配的活动，可以试试补充时间、氛围或活动类型"));
        }

        // 判断是否需要健康免责声明（减脂/低糖/控碳水/养胃槽位）
        boolean needDisclaimer = needsDisclaimer(slots);
        try {
            // 从 AgentFactory 获取 RecommendResponseAgent 实例
            ReActAgent agent = agentFactory.get(sessionId).recommendResponse();
            // 清空 Agent 内存
            agent.getMemory().clear();
            // 调用 Agent：内部走 agentTraceService.callAgent，记录 AGENT_CALL（RecommendResponseAgent + main-model）
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "RecommendResponseAgent",
                    modelName,
                    agent,
                    buildUserPrompt(userInput, sourceMode, slots, topMeals)
            );
            // 解析 Agent JSON 输出为 recommendations + speechText
            ParsedOutput parsed = parseOutput(response.getTextContent(), topMeals, slots);

            // 构造 RecommendResult：推荐项列表 + strategy + needDisclaimer
            RecommendResult recommend = new RecommendResult(parsed.options(), needDisclaimer);

            // 构造 ResponseResult：speechText + 前端卡片 displayBlocks + nextAction=WAIT_USER
            ResponseResult responseResult = new ResponseResult(parsed.speechText(), toDisplayBlocks(recommend), "WAIT_USER");
            return new Result(recommend, responseResult);

        } catch (Exception ignored) {
            // LLM 异常时用模板理由 + 模板 speechText 兜底
            RecommendResult recommend = new RecommendResult(templateOptions(topMeals, slots), needDisclaimer);
            return new Result(recommend, new ResponseResult(templateSpeech(recommend), toDisplayBlocks(recommend), "WAIT_USER"));
        }
    }

    /**
     * 构造 RecommendResponseAgent 的输入 prompt。
     */
    private String buildUserPrompt(String userInput, SourceMode sourceMode, SlotBundle slots, List<ActivityItem> topMeals) {
        return """
                用户原话：%s
                数据源模式：%s
                本轮槽位：%s
                候选活动：%s
                请输出 JSON，包含 recommendations 数组（每项 activityId + reason）和 speechText，不要编造候选之外的活动。
                """.formatted(userInput, sourceMode, slots, topMeals);
    }

    /**
     * 解析 Agent 返回的 JSON 为 ParsedOutput。
     */
    private ParsedOutput parseOutput(String content, List<ActivityItem> topMeals, SlotBundle slots) {
        JsonNode root = llmJsonService.parseObject(content);                              // 提取 JSON 根节点
        List<RecommendedActivityOption> options = parseOptions(root.path("recommendations"), topMeals, slots); // 解析推荐数组
        String speechText = root.path("speechText").asText("").trim();                    // 读取口语回复
        if (speechText.isBlank()) {
            speechText = templateSpeech(new RecommendResult(options, needsDisclaimer(slots))); // 空则用模板
        }
        return new ParsedOutput(options, speechText);
    }

    /**
     * 解析 recommendations JSON 数组，只保留 topMeals 中存在的 activityId。
     */
    private List<RecommendedActivityOption> parseOptions(JsonNode recommendationsNode, List<ActivityItem> topMeals, SlotBundle slots) {
        Map<Long, ActivityItem> byId = new LinkedHashMap<>();
        topMeals.forEach(activity -> byId.put(activity.id(), activity));           // activityId → ActivityItem 索引
        Map<Long, String> reasons = new LinkedHashMap<>();
        if (recommendationsNode.isArray()) {
            recommendationsNode.forEach(node -> {
                // 兼容 activityId 和 itemId 两种字段名
                long activityId = node.path("activityId").isMissingNode() ? node.path("itemId").asLong() : node.path("activityId").asLong();
                String reason = node.path("reason").asText("");
                // 只采纳候选内且 reason 非空的项
                if (byId.containsKey(activityId) && !reason.isBlank()) {
                    reasons.put(activityId, reason);
                }
            });
        }
        List<RecommendedActivityOption> result = new ArrayList<>();
        // 按 topMeals 顺序输出，缺失 reason 时用 templateReason 兜底
        for (ActivityItem activity : topMeals) {
            result.add(toOption(activity, reasons.getOrDefault(activity.id(), templateReason(activity, slots))));
        }
        return result;
    }

    /**
     * LLM 失败时为 topMeals 生成模板推荐理由列表。
     */
    private List<RecommendedActivityOption> templateOptions(List<ActivityItem> topMeals, SlotBundle slots) {
        return topMeals.stream()
                .map(activity -> toOption(activity, templateReason(activity, slots)))
                .toList();
    }

    /**
     * ActivityItem + reason 转为 RecommendedActivityOption。
     */
    private RecommendedActivityOption toOption(ActivityItem activity, String reason) {
        return new RecommendedActivityOption(activity.id(), activity.sourceType(), activity.name(), reason, activity.matchScore(), activity.slots());
    }

    /**
     * 根据 slots 生成单条模板推荐理由。
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
     * 将 RecommendResult 转为前端展示用的 ActivityResponse 卡片列表。
     */
    private List<ActivityResponse> toDisplayBlocks(RecommendResult recommendResult) {
        if (recommendResult == null || recommendResult.recommendations() == null) {
            return List.of();
        }
        return recommendResult.recommendations().stream()
                .map(this::toActivityResponse)
                .toList();
    }

    /**
     * RecommendedActivityOption 转为 ActivityResponse（含各维 slots 标签）。
     */
    private ActivityResponse toActivityResponse(RecommendedActivityOption option) {
        return new ActivityResponse(
                option.itemId(),
                option.sourceType(),
                option.name(),
                option.matchedSlots().city(),
                option.matchedSlots().location(),
                option.matchedSlots().activityTime(),
                option.matchedSlots().mood(),
                option.matchedSlots().scene(),
                option.matchedSlots().budget(),
                option.matchedSlots().activityType(),
                option.matchedSlots().style(),
                option.matchedSlots().duration(),
                option.matchScore()
        );
    }

    /**
     * LLM 失败时的模板口语回复，含可选免责声明。
     */
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
     * 健康相关槽位命中时需附加免责声明。
     */
    private boolean needsDisclaimer(SlotBundle slots) {
        return slots != null && slots.budget().stream().anyMatch(value ->
                value.contains("减脂") || value.contains("低糖") || value.contains("控碳水") || value.contains("养胃"));
    }

    /**
     * recommendAndRespond 的返回结构：RecommendResult + ResponseResult。
     */
    public record Result(RecommendResult recommend, ResponseResult response) {
    }

    /**
     * parseOutput 的中间结构。
     */
    private record ParsedOutput(List<RecommendedActivityOption> options, String speechText) {
    }
}

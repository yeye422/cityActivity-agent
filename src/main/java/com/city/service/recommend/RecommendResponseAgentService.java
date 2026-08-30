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
 * RecommendResponseAgent 服务（Orchestrator 推荐流水线第三层）。
 * 一次 LLM 调用同时生成 top3 推荐理由和面向用户的口语 speechText。
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
            @Value("${diet.llm.main-model:qwen-max}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    public Result recommendAndRespond(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle slots,
            List<ActivityItem> rankedMeals) {
        return recommendAndRespond(sessionId, userInput, sourceMode, slots, rankedMeals, WeatherRecommendationContext.inactive());
    }

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
            RecommendResult recommend = new RecommendResult(templateOptions(topMeals, slots), needDisclaimer);
            return new Result(
                    recommend,
                    new ResponseResult(templateSpeech(recommend), toDisplayBlocks(recommend, topMeals), "WAIT_USER")
            );
        }
    }

    private String buildUserPrompt(String userInput, SourceMode sourceMode, SlotBundle slots, List<ActivityItem> topMeals,
                                   WeatherRecommendationContext weather) {
        return """
                用户原话：%s
                数据源模式：%s
                本轮槽位：%s
                候选活动：%s
                天气排序说明：%s
                请输出 JSON，包含 recommendations 数组（每项 activityId + reason）和 speechText，不要编造候选之外的活动。
                """.formatted(userInput, sourceMode, slots, topMeals,
                weather != null && weather.active() ? weather.summary() : "未启用天气排序");
    }

    private ParsedOutput parseOutput(String content, List<ActivityItem> topMeals, SlotBundle slots) {
        JsonNode root = llmJsonService.parseObject(content);
        List<RecommendedActivityOption> options = parseOptions(root.path("recommendations"), topMeals, slots);
        String speechText = root.path("speechText").asText("").trim();
        if (speechText.isBlank()) {
            speechText = templateSpeech(new RecommendResult(options, needsDisclaimer(slots)));
        }
        return new ParsedOutput(options, speechText);
    }

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

    private List<RecommendedActivityOption> templateOptions(List<ActivityItem> topMeals, SlotBundle slots) {
        return topMeals.stream()
                .map(activity -> toOption(activity, templateReason(activity, slots)))
                .toList();
    }

    private RecommendedActivityOption toOption(ActivityItem activity, String reason) {
        return new RecommendedActivityOption(
                activity.id(), activity.sourceType(), activity.name(), reason, activity.matchScore(), activity.slots());
    }

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

    private boolean needsDisclaimer(SlotBundle slots) {
        return slots != null && slots.budget().stream().anyMatch(value ->
                value.contains("减脂") || value.contains("低糖") || value.contains("控碳水") || value.contains("养胃"));
    }

    public record Result(RecommendResult recommend, ResponseResult response) {
    }

    private record ParsedOutput(List<RecommendedActivityOption> options, String speechText) {
    }
}

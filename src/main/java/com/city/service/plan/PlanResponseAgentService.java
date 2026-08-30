package com.city.service.plan;

import com.city.agent.factory.AgentFactory;
import com.city.enums.SourceMode;
import com.city.model.*;
import com.city.service.recommend.RecommendResponseAgentService;
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
 * 多时段规划应答 Agent：按活动时段生成理由与结构化口语回复。
 */
@Service
public class PlanResponseAgentService {

    private final AgentFactory agentFactory;
    private final LlmJsonService llmJsonService;
    private final AgentTraceService agentTraceService;
    private final String modelName;

    public PlanResponseAgentService(
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
     * 将按时段选出的方案包装为 RecommendResult + ResponseResult。
     */
    public RecommendResponseAgentService.Result planAndRespond(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle sharedSlots,
            List<ActivityPlanService.PlannedActivity> plannedMeals
    ) {
        List<ActivityPlanService.PlannedActivity> safePlans = plannedMeals == null ? List.of() : plannedMeals;
        List<ActivityPlanService.PlannedActivity> matched = safePlans.stream().filter(ActivityPlanService.PlannedActivity::matched).toList();
        boolean needDisclaimer = needsDisclaimer(sharedSlots);

        if (matched.isEmpty()) {
            RecommendResult empty = RecommendResult.empty();
            return new RecommendResponseAgentService.Result(
                    empty,
                    ResponseResult.textOnly("暂时没有拼出完整的多时段方案，你可以补充氛围、活动类型，或调整时间安排后再试。")
            );
        }

        try {
            ReActAgent agent = agentFactory.get(sessionId).planResponse();
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "PlanResponseAgent",
                    modelName,
                    agent,
                    buildUserPrompt(userInput, sourceMode, sharedSlots, safePlans)
            );
            ParsedOutput parsed = parseOutput(response.getTextContent(), safePlans, sharedSlots);
            RecommendResult recommend = new RecommendResult(parsed.options(), needDisclaimer);
            ResponseResult responseResult = new ResponseResult(parsed.speechText(), toDisplayBlocks(recommend), "WAIT_USER");
            return new RecommendResponseAgentService.Result(recommend, responseResult);
        } catch (Exception ignored) {
            RecommendResult recommend = new RecommendResult(templateOptions(safePlans, sharedSlots), needDisclaimer);
            return new RecommendResponseAgentService.Result(
                    recommend,
                    new ResponseResult(templateSpeech(safePlans, recommend), toDisplayBlocks(recommend), "WAIT_USER")
            );
        }
    }

    private String buildUserPrompt(
            String userInput,
            SourceMode sourceMode,
            SlotBundle sharedSlots,
            List<ActivityPlanService.PlannedActivity> plannedMeals
    ) {
        StringBuilder mealSection = new StringBuilder();
        for (ActivityPlanService.PlannedActivity planned : plannedMeals) {
            mealSection.append("\n- 时段=").append(planned.period());
            if (planned.matched()) {
                ActivityItem activity = planned.activity();
                mealSection.append("，候选=[activityId=").append(activity.id())
                        .append(", name=").append(activity.name())
                        .append(", score=").append(activity.matchScore())
                        .append("]");
            } else {
                mealSection.append("，候选=[]（暂无匹配）");
            }
        }
        return """
                用户原话：%s
                数据源模式：%s
                共享槽位：%s
                各时段候选：%s
                请输出 JSON，包含 mealPlans 数组（每项 period + activityId + reason）和 speechText；activityId 必须来自对应时段候选。
                """.formatted(userInput, sourceMode, sharedSlots, mealSection);
    }

    private ParsedOutput parseOutput(String content, List<ActivityPlanService.PlannedActivity> plannedMeals, SlotBundle sharedSlots) {
        JsonNode root = llmJsonService.parseObject(content);
        Map<String, String> reasonsByActivityTime = new LinkedHashMap<>();
        JsonNode plansNode = root.path("mealPlans");
        if (plansNode.isArray()) {
            plansNode.forEach(node -> {
                String period = node.path("period").asText("").trim();
                String reason = node.path("reason").asText("").trim();
                if (!period.isBlank() && !reason.isBlank()) {
                    reasonsByActivityTime.put(period, reason);
                }
            });
        }

        List<RecommendedActivityOption> options = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity planned : plannedMeals) {
            if (!planned.matched()) {
                continue;
            }
            ActivityItem activity = planned.activity();
            // 以 Java 已选活动为准，避免 LLM 跨时段挪用 activityId
            String reason = reasonsByActivityTime.getOrDefault(planned.period(), templateReason(planned, sharedSlots));
            options.add(toOption(activity, reason, planned.querySlots()));
        }

        String speechText = root.path("speechText").asText("").trim();
        if (speechText.isBlank()) {
            speechText = templateSpeech(plannedMeals, new RecommendResult(options, needsDisclaimer(sharedSlots)));
        }
        return new ParsedOutput(options, speechText);
    }

    private List<RecommendedActivityOption> templateOptions(List<ActivityPlanService.PlannedActivity> plannedMeals, SlotBundle sharedSlots) {
        List<RecommendedActivityOption> options = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity planned : plannedMeals) {
            if (!planned.matched()) {
                continue;
            }
            options.add(toOption(planned.activity(), templateReason(planned, sharedSlots), planned.querySlots()));
        }
        return options;
    }

    private RecommendedActivityOption toOption(ActivityItem activity, String reason, SlotBundle querySlots) {
        SlotBundle displaySlots = querySlots != null ? querySlots : activity.slots();
        return new RecommendedActivityOption(activity.id(), activity.sourceType(), activity.name(), reason, activity.matchScore(), displaySlots);
    }

    private String templateReason(ActivityPlanService.PlannedActivity planned, SlotBundle sharedSlots) {
        String name = planned.activity().name();
        if (sharedSlots != null && !sharedSlots.budget().isEmpty()) {
            return name + "比较符合你提到的" + String.join("、", sharedSlots.budget()) + "诉求，适合作为" + planned.period() + "。";
        }
        if (sharedSlots != null && !sharedSlots.style().isEmpty()) {
            return name + "比较贴近你想要的" + String.join("、", sharedSlots.style()) + "活动风格，适合" + planned.period() + "。";
        }
        return name + "和你的偏好匹配度较高，适合安排在" + planned.period() + "。";
    }

    private String templateSpeech(List<ActivityPlanService.PlannedActivity> plannedMeals, RecommendResult recommendResult) {
        StringBuilder builder = new StringBuilder("为你规划了一套时间安排：");
        for (ActivityPlanService.PlannedActivity planned : plannedMeals) {
            builder.append("\n- ").append(planned.period()).append("：");
            if (planned.matched()) {
                String reason = recommendResult.recommendations().stream()
                        .filter(option -> option.itemId().equals(planned.activity().id()))
                        .map(RecommendedActivityOption::reason)
                        .findFirst()
                        .orElse(planned.activity().name());
                builder.append(planned.activity().name()).append("（").append(reason).append("）");
            } else {
                builder.append("暂时没有很匹配的活动");
            }
        }
        builder.append("\n想换其中某一段，或者整体再安静/热闹一点，直接跟我说就行。");
        if (recommendResult.needDisclaimer()) {
            builder.append("\n这些建议仅供周末娱乐参考，具体安排请根据实际情况调整。");
        }
        return builder.toString();
    }

    private List<ActivityResponse> toDisplayBlocks(RecommendResult recommendResult) {
        if (recommendResult == null || recommendResult.recommendations() == null) {
            return List.of();
        }
        return recommendResult.recommendations().stream()
                .map(option -> new ActivityResponse(
                        option.itemId(),
                        option.sourceType(),
                        option.name(),
                        option.matchedSlots().city(),
                        option.matchedSlots().location(),
                        option.matchedSlots().mood(),
                        option.matchedSlots().scene(),
                        option.matchedSlots().budget(),
                        option.matchedSlots().activityType(),
                        option.matchedSlots().style(),
                        option.matchedSlots().duration(),
                        null,
                        null,
                        null,
                        null,
                        option.matchScore()
                ))
                .toList();
    }

    private boolean needsDisclaimer(SlotBundle slots) {
        return slots != null && slots.budget().stream().anyMatch(value ->
                value.contains("减脂") || value.contains("低糖") || value.contains("控碳水") || value.contains("养胃"));
    }

    private record ParsedOutput(List<RecommendedActivityOption> options, String speechText) {
    }
}

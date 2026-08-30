package com.city.service.plan;

import com.city.agent.factory.AgentFactory;
import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityResponse;
import com.city.model.RecommendResult;
import com.city.model.RecommendedActivityOption;
import com.city.model.ResponseResult;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
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
 * 多时段规划应答 Agent：Java 先固定每个时段活动，LLM 只基于完整候选事实生成理由与口语回复。
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
            @Value("${diet.llm.response-model:qwen-turbo}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    public RecommendResponseAgentService.Result planAndRespond(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle sharedSlots,
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            WeatherRecommendationContext weather
    ) {
        List<ActivityPlanService.PlannedActivity> safePlans = plannedActivities == null ? List.of() : plannedActivities;
        List<ActivityPlanService.PlannedActivity> matched = safePlans.stream()
                .filter(ActivityPlanService.PlannedActivity::matched)
                .toList();
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
                    buildUserPrompt(userInput, sourceMode, sharedSlots, safePlans, weather)
            );
            ParsedOutput parsed = parseOutput(response.getTextContent(), safePlans, sharedSlots);
            RecommendResult recommend = new RecommendResult(parsed.options(), needDisclaimer);
            ResponseResult responseResult = new ResponseResult(
                    parsed.speechText(), toDisplayBlocks(recommend, safePlans), "WAIT_USER");
            return new RecommendResponseAgentService.Result(recommend, responseResult);
        } catch (Exception ignored) {
            RecommendResult recommend = new RecommendResult(templateOptions(safePlans, sharedSlots), needDisclaimer);
            return new RecommendResponseAgentService.Result(
                    recommend,
                    new ResponseResult(
                            templateSpeech(safePlans, recommend),
                            toDisplayBlocks(recommend, safePlans),
                            "WAIT_USER")
            );
        }
    }

    private String buildUserPrompt(
            String userInput,
            SourceMode sourceMode,
            SlotBundle sharedSlots,
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            WeatherRecommendationContext weather
    ) {
        StringBuilder activitySection = new StringBuilder();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            activitySection.append("\n- period=").append(planned.period());
            if (planned.matched()) {
                ActivityItem activity = planned.activity();
                activitySection.append(", activity={")
                        .append("activityId=").append(activity.id())
                        .append(", name=").append(activity.name())
                        .append(", slots=").append(activity.slots())
                        .append(", validFrom=").append(activity.validFrom())
                        .append(", validTo=").append(activity.validTo())
                        .append(", validStartTime=").append(activity.validStartTime())
                        .append(", validEndTime=").append(activity.validEndTime())
                        .append(", matchScore=").append(activity.matchScore())
                        .append("}");
            } else {
                activitySection.append(", activity=null（该时段暂无匹配）");
            }
        }
        String weatherSummary = weather != null && weather.active()
                ? weather.summary()
                : "未启用天气排序";
        return """
                用户原话：%s
                数据源模式：%s
                用户共享条件：%s
                天气排序说明：%s
                Java 已选定的各时段活动：%s

                请输出一个合法 JSON 对象：
                {"mealPlans":[{"period":"上午","activityId":1,"reason":"..."}],"speechText":"..."}

                规则：
                - activityId 必须与对应 period 的 Java 已选活动完全一致，不能换活动、补活动或跨时段挪用 activityId。
                - reason 和 speechText 只能使用上面给出的活动 facts、用户条件和天气说明，不要根据活动名称脑补地址、价格、具体玩法、距离、开放状态或主观体验。
                - 每个时段用 1 句说明最有区分度的匹配理由，表达自然但不要机械罗列字段。
                - 某时段 activity=null 时可以简短说明暂无匹配，不要自行创造候选。
                """.formatted(userInput, sourceMode, sharedSlots, weatherSummary, activitySection);
    }

    private ParsedOutput parseOutput(String content,
                                     List<ActivityPlanService.PlannedActivity> plannedActivities,
                                     SlotBundle sharedSlots) {
        JsonNode root = llmJsonService.parseObject(content);
        Map<String, ActivityPlanService.PlannedActivity> plansByPeriod = new LinkedHashMap<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            plansByPeriod.put(planned.period(), planned);
        }

        Map<String, String> reasonsByPeriod = new LinkedHashMap<>();
        JsonNode plansNode = root.path("mealPlans");
        if (plansNode.isArray()) {
            plansNode.forEach(node -> {
                String period = node.path("period").asText("").trim();
                long activityId = node.path("activityId").asLong(0L);
                String reason = node.path("reason").asText("").trim();
                ActivityPlanService.PlannedActivity expected = plansByPeriod.get(period);
                if (expected != null
                        && expected.matched()
                        && expected.activity().id() != null
                        && expected.activity().id() == activityId
                        && !reason.isBlank()) {
                    reasonsByPeriod.put(period, reason);
                }
            });
        }

        List<RecommendedActivityOption> options = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            if (!planned.matched()) {
                continue;
            }
            ActivityItem activity = planned.activity();
            String reason = reasonsByPeriod.getOrDefault(
                    planned.period(), templateReason(planned, sharedSlots));
            options.add(toOption(activity, reason, planned.querySlots()));
        }

        String speechText = root.path("speechText").asText("").trim();
        if (speechText.isBlank()) {
            speechText = templateSpeech(
                    plannedActivities, new RecommendResult(options, needsDisclaimer(sharedSlots)));
        }
        return new ParsedOutput(options, speechText);
    }

    private List<RecommendedActivityOption> templateOptions(
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            SlotBundle sharedSlots) {
        List<RecommendedActivityOption> options = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            if (!planned.matched()) {
                continue;
            }
            options.add(toOption(
                    planned.activity(),
                    templateReason(planned, sharedSlots),
                    planned.querySlots()));
        }
        return options;
    }

    private RecommendedActivityOption toOption(ActivityItem activity, String reason, SlotBundle querySlots) {
        SlotBundle displaySlots = querySlots != null ? querySlots : activity.slots();
        return new RecommendedActivityOption(
                activity.id(), activity.sourceType(), activity.name(), reason, activity.matchScore(), displaySlots);
    }

    private String templateReason(ActivityPlanService.PlannedActivity planned, SlotBundle sharedSlots) {
        String name = planned.activity().name();
        if (sharedSlots != null && !sharedSlots.budget().isEmpty()) {
            return name + "符合你当前的预算条件，适合作为" + planned.period() + "的候选。";
        }
        if (sharedSlots != null && !sharedSlots.style().isEmpty()) {
            return name + "比较贴近你想要的" + String.join("、", sharedSlots.style()) + "风格，适合安排在" + planned.period() + "。";
        }
        return name + "和你当前条件匹配度较高，适合安排在" + planned.period() + "。";
    }

    private String templateSpeech(
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            RecommendResult recommendResult) {
        StringBuilder builder = new StringBuilder("可以按这个顺序安排：");
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
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
        builder.append("\n如果想换其中某一段，可以直接告诉我具体时段和新偏好。");
        if (recommendResult.needDisclaimer()) {
            builder.append("\n这些建议仅供周末娱乐参考，具体安排请根据实际情况调整。");
        }
        return builder.toString();
    }

    /** 规划卡片始终以 Java 选定的原始 ActivityItem 为事实来源。 */
    private List<ActivityResponse> toDisplayBlocks(
            RecommendResult recommendResult,
            List<ActivityPlanService.PlannedActivity> plans) {
        if (recommendResult == null || recommendResult.recommendations() == null
                || plans == null || plans.isEmpty()) {
            return List.of();
        }
        Map<Long, ActivityItem> byId = new LinkedHashMap<>();
        for (ActivityPlanService.PlannedActivity planned : plans) {
            if (planned != null && planned.matched()) {
                ActivityItem activity = planned.activity();
                byId.putIfAbsent(activity.id(), activity);
            }
        }
        return recommendResult.recommendations().stream()
                .map(option -> option == null ? null : byId.get(option.itemId()))
                .filter(activity -> activity != null)
                .map(ActivityResponse::from)
                .toList();
    }

    private boolean needsDisclaimer(SlotBundle slots) {
        return slots != null && slots.budget().stream().anyMatch(value ->
                value.contains("减脂") || value.contains("低糖") || value.contains("控碳水") || value.contains("养胃"));
    }

    private record ParsedOutput(List<RecommendedActivityOption> options, String speechText) {
    }
}

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

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多时段规划 Agent：Java 负责生成合法候选空间，Agent 负责决定哪些时段值得安排、
 * 每个已安排时段选哪个候选，以及整体组合如何兼顾相关性、多样性和区域连贯性。
 * Java 最后验证候选归属、重复活动和时间冲突；非法选择才退化为确定性 fallback。
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
        boolean hasCandidate = safePlans.stream().anyMatch(plan -> !candidatePool(plan).isEmpty());
        boolean needDisclaimer = needsDisclaimer(sharedSlots);

        if (!hasCandidate) {
            RecommendResult empty = RecommendResult.empty();
            return new RecommendResponseAgentService.Result(
                    empty,
                    ResponseResult.textOnly("当前这些时段里还没有找到足够合适的活动，我先不硬凑。如果你愿意，可以放宽一点活动类型、区域或其他偏好，我再帮你补一版。")
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
                    parsed.speechText(), toDisplayBlocks(recommend, parsed.selectedPlans()), "WAIT_USER");
            return new RecommendResponseAgentService.Result(recommend, responseResult);
        } catch (Exception ignored) {
            List<ActivityPlanService.PlannedActivity> fallbackPlans = deterministicFallback(safePlans);
            RecommendResult recommend = new RecommendResult(templateOptions(fallbackPlans, sharedSlots), needDisclaimer);
            return new RecommendResponseAgentService.Result(
                    recommend,
                    new ResponseResult(
                            templateSpeech(fallbackPlans, recommend, Map.of()),
                            toDisplayBlocks(recommend, fallbackPlans),
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
            List<ActivityItem> candidates = candidatePool(planned);
            activitySection.append("\n- period=").append(planned.period()).append(", candidates=[");
            for (int i = 0; i < candidates.size(); i++) {
                ActivityItem activity = candidates.get(i);
                if (i > 0) {
                    activitySection.append(", ");
                }
                activitySection.append("{")
                        .append("activityId=").append(activity.id())
                        .append(", name=").append(activity.name())
                        .append(", slots=").append(activity.slots())
                        .append(", validFrom=").append(activity.validFrom())
                        .append(", validTo=").append(activity.validTo())
                        .append(", validStartTime=").append(activity.validStartTime())
                        .append(", validEndTime=").append(activity.validEndTime())
                        .append(", matchScore=").append(activity.matchScore())
                        .append("}");
            }
            activitySection.append("]");
        }
        String weatherSummary = weather != null && weather.active()
                ? weather.summary()
                : "未启用天气排序";
        return """
                用户原话：%s
                数据源模式：%s
                用户共享条件：%s
                天气排序说明：%s
                Java 为各时段生成的已排序候选池：%s

                请只输出一个合法 JSON 对象，顶层只能包含 mealPlans 和 speechText。
                mealPlans 必须覆盖上面列出的每一个 period，并为每个 period 明确给出 decision=SELECT 或 decision=SKIP。

                SELECT 时：
                - 必须输出 period、decision、activityId、reason。
                - activityId 必须来自该 period 自己的 candidates。

                SKIP 时：
                - 必须输出 period、decision、reason。
                - 不要输出 activityId，或将 activityId 设为 null。
                - SKIP 表示你主动认为该时段不值得为了凑满行程而安排活动，不代表系统失败。

                规划目标：
                - 你可以只选择 1 个或多个真正合适的活动，不要求覆盖所有时段。
                - 相关性优先：明显弱的候选可以 SKIP，不要为了凑满行程牺牲匹配度。
                - 在相关性接近时，提高活动类型多样性，并优先 location 更连贯的组合。
                - 结合候选的 validStartTime / validEndTime，避免选择时间明显重叠的活动。
                - 不同 period 不得重复使用同一个 activityId。
                - 某个 period 即使 candidates 非空，也允许基于整体组合主动 SKIP。

                强制规则：
                - 每个 period 最多 SELECT 1 个活动。
                - 禁止跨时段选取、编造 activityId 或新增候选。
                - candidates=[] 的时段必须 SKIP，不能从别的时段借活动填充。
                - reason 和 speechText 只能使用候选明确提供的 facts、用户条件和天气说明，不要根据活动名称脑补地址、价格、具体玩法、真实距离、开放状态或主观体验。
                - speechText 要像真正帮用户排计划：先说整体安排，再自然解释哪些时段被选中、哪些时段主动留空以及为什么。
                - 对主动 SKIP 的时段，不要生硬写“暂无匹配”；可以表达成“这一段先留空更合适”“不为了凑满硬塞一个活动”。
                - 如果只有一两个时段值得安排，也直接给出部分行程，不要把它描述成规划失败。
                - 禁止输出“...”、示例占位理由、候选中不存在的 period 或 activityId。
                - 最终只输出 JSON，不要输出 Markdown 代码块或 JSON 之外的文字。
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

        Map<String, AgentDecision> requestedByPeriod = new LinkedHashMap<>();
        boolean invalidStructure = false;
        JsonNode plansNode = root.path("mealPlans");
        if (!plansNode.isArray()) {
            invalidStructure = true;
        } else {
            for (JsonNode node : plansNode) {
                String period = node.path("period").asText("").trim();
                String decision = node.path("decision").asText("").trim().toUpperCase();
                String reason = node.path("reason").asText("").trim();
                ActivityPlanService.PlannedActivity expected = plansByPeriod.get(period);

                if (expected == null || requestedByPeriod.containsKey(period)) {
                    invalidStructure = true;
                    continue;
                }

                if ("SKIP".equals(decision)) {
                    requestedByPeriod.put(period, AgentDecision.skip(reason));
                    continue;
                }

                if ("SELECT".equals(decision)) {
                    long activityId = node.path("activityId").asLong(0L);
                    ActivityItem selected = findCandidate(expected, activityId);
                    if (selected != null && !"...".equals(reason)) {
                        requestedByPeriod.put(period, AgentDecision.select(selected, reason));
                    } else {
                        invalidStructure = true;
                    }
                    continue;
                }

                invalidStructure = true;
            }
        }

        List<ActivityPlanService.PlannedActivity> selectedPlans = new ArrayList<>();
        List<RecommendedActivityOption> options = new ArrayList<>();
        Set<Long> usedIds = new LinkedHashSet<>();
        Map<String, String> unfilledReasons = new LinkedHashMap<>();
        boolean adjusted = invalidStructure || requestedByPeriod.size() != plannedActivities.size();

        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            AgentDecision requested = requestedByPeriod.get(planned.period());

            if (requested != null && requested.skip()) {
                selectedPlans.add(new ActivityPlanService.PlannedActivity(
                        planned.period(), null, planned.querySlots(), candidatePool(planned)));
                unfilledReasons.put(planned.period(), normalizeSkipReason(requested.reason(), planned));
                continue;
            }

            ActivityItem selected = requested == null ? null : requested.activity();
            String reason = requested == null ? "" : requested.reason();

            if (selected == null
                    || usedIds.contains(selected.id())
                    || hasTimeConflict(planned.period(), selected, selectedPlans)) {
                selected = fallbackCandidate(planned, usedIds, selectedPlans);
                reason = "";
                if (!candidatePool(planned).isEmpty()) {
                    adjusted = true;
                }
            }

            if (selected == null) {
                selectedPlans.add(new ActivityPlanService.PlannedActivity(
                        planned.period(), null, planned.querySlots(), candidatePool(planned)));
                if (candidatePool(planned).isEmpty()) {
                    unfilledReasons.put(planned.period(), "当前候选池为空，这一段先留空更合适");
                } else {
                    unfilledReasons.put(planned.period(), "现有候选和前面的安排时间上不够顺，这一段先留空更合适");
                }
                continue;
            }

            usedIds.add(selected.id());
            ActivityPlanService.PlannedActivity selectedPlan = new ActivityPlanService.PlannedActivity(
                    planned.period(), selected, planned.querySlots(), candidatePool(planned));
            selectedPlans.add(selectedPlan);
            if (reason.isBlank()) {
                reason = templateReason(selectedPlan, sharedSlots);
            }
            options.add(toOption(selected, reason, planned.querySlots()));
        }

        String speechText = root.path("speechText").asText("").trim();
        if (speechText.isBlank() || speechText.equals("...") || adjusted) {
            speechText = templateSpeech(
                    selectedPlans,
                    new RecommendResult(options, needsDisclaimer(sharedSlots)),
                    unfilledReasons
            );
        }
        return new ParsedOutput(options, speechText, selectedPlans);
    }

    private String normalizeSkipReason(String reason, ActivityPlanService.PlannedActivity planned) {
        if (reason != null && !reason.isBlank() && !"...".equals(reason)) {
            return reason;
        }
        if (candidatePool(planned).isEmpty()) {
            return "当前没有合适候选，这一段先留空";
        }
        return "为了整体行程质量，这一段先留空，不为了凑满硬塞活动";
    }

    private ActivityItem findCandidate(ActivityPlanService.PlannedActivity planned, long activityId) {
        if (planned == null || activityId <= 0) {
            return null;
        }
        return candidatePool(planned).stream()
                .filter(activity -> activity.id() != null && activity.id() == activityId)
                .findFirst()
                .orElse(null);
    }

    private ActivityItem fallbackCandidate(ActivityPlanService.PlannedActivity planned,
                                           Set<Long> usedIds,
                                           List<ActivityPlanService.PlannedActivity> alreadySelected) {
        if (planned == null) {
            return null;
        }
        return candidatePool(planned).stream()
                .filter(activity -> activity.id() != null)
                .filter(activity -> !usedIds.contains(activity.id()))
                .filter(activity -> !hasTimeConflict(planned.period(), activity, alreadySelected))
                .findFirst()
                .orElse(null);
    }

    /**
     * Java 只做可客观验证的时间冲突检查：同一规划日内，两个活动的有效时间窗口真实重叠则冲突。
     * 没有完整时间字段时不猜，由 Agent 的组合判断和候选排序继续负责。
     */
    private boolean hasTimeConflict(String period,
                                    ActivityItem candidate,
                                    List<ActivityPlanService.PlannedActivity> selectedPlans) {
        if (candidate == null || selectedPlans == null || selectedPlans.isEmpty()) {
            return false;
        }
        for (ActivityPlanService.PlannedActivity selectedPlan : selectedPlans) {
            if (selectedPlan == null || !selectedPlan.matched()) {
                continue;
            }
            if (!samePlanningDay(period, selectedPlan.period())) {
                continue;
            }
            if (activitiesOverlap(candidate, selectedPlan.activity())) {
                return true;
            }
        }
        return false;
    }

    private boolean activitiesOverlap(ActivityItem first, ActivityItem second) {
        if (first == null || second == null) {
            return false;
        }
        LocalTime firstStart = first.validStartTime();
        LocalTime firstEnd = first.validEndTime();
        LocalTime secondStart = second.validStartTime();
        LocalTime secondEnd = second.validEndTime();
        if (firstStart == null || firstEnd == null || secondStart == null || secondEnd == null) {
            return false;
        }
        return firstStart.isBefore(secondEnd) && firstEnd.isAfter(secondStart);
    }

    private boolean samePlanningDay(String firstPeriod, String secondPeriod) {
        return planningDayKey(firstPeriod).equals(planningDayKey(secondPeriod));
    }

    private String planningDayKey(String period) {
        if (period != null && period.contains("周六")) {
            return "SATURDAY";
        }
        if (period != null && period.contains("周日")) {
            return "SUNDAY";
        }
        return "SAME_DAY";
    }

    private List<ActivityPlanService.PlannedActivity> deterministicFallback(
            List<ActivityPlanService.PlannedActivity> plannedActivities) {
        List<ActivityPlanService.PlannedActivity> result = new ArrayList<>();
        Set<Long> usedIds = new LinkedHashSet<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            ActivityItem selected = fallbackCandidate(planned, usedIds, result);
            if (selected != null) {
                usedIds.add(selected.id());
            }
            result.add(new ActivityPlanService.PlannedActivity(
                    planned.period(), selected, planned.querySlots(), candidatePool(planned)));
        }
        return result;
    }

    private List<ActivityItem> candidatePool(ActivityPlanService.PlannedActivity planned) {
        if (planned == null) {
            return List.of();
        }
        if (planned.candidates() != null && !planned.candidates().isEmpty()) {
            return planned.candidates();
        }
        return planned.activity() == null ? List.of() : List.of(planned.activity());
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
            return name + "符合你当前的预算条件，放在" + planned.period() + "比较合适。";
        }
        if (sharedSlots != null && !sharedSlots.style().isEmpty()) {
            return name + "比较贴近你想要的" + String.join("、", sharedSlots.style()) + "风格，放在" + planned.period() + "比较合适。";
        }
        return name + "和你当前条件匹配度较高，放在" + planned.period() + "比较合适。";
    }

    private String templateSpeech(
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            RecommendResult recommendResult,
            Map<String, String> unfilledReasons) {
        List<ActivityPlanService.PlannedActivity> matchedPlans = plannedActivities.stream()
                .filter(ActivityPlanService.PlannedActivity::matched)
                .toList();
        List<ActivityPlanService.PlannedActivity> unfilledPlans = plannedActivities.stream()
                .filter(planned -> !planned.matched())
                .toList();

        StringBuilder builder = new StringBuilder();
        if (!matchedPlans.isEmpty()) {
            builder.append("这版我不强求把每个时段都塞满，先保留更顺的安排：");
            for (ActivityPlanService.PlannedActivity planned : matchedPlans) {
                String reason = recommendResult.recommendations().stream()
                        .filter(option -> option.itemId().equals(planned.activity().id()))
                        .map(RecommendedActivityOption::reason)
                        .findFirst()
                        .orElse(planned.activity().name());
                builder.append("\n- ").append(planned.period()).append("：")
                        .append(planned.activity().name()).append("（").append(reason).append("）");
            }
        }

        if (!unfilledPlans.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            for (int i = 0; i < unfilledPlans.size(); i++) {
                ActivityPlanService.PlannedActivity planned = unfilledPlans.get(i);
                if (i > 0) {
                    builder.append("\n");
                }
                String reason = unfilledReasons == null
                        ? "这一段先留空更合适"
                        : unfilledReasons.getOrDefault(planned.period(), "这一段先留空更合适");
                builder.append(planned.period()).append("这段先不硬凑：").append(reason).append("。");
            }
            builder.append("\n如果你想把留空的时段也补上，可以告诉我更想保留哪种活动，或者只放宽那一段的条件。");
        } else if (!matchedPlans.isEmpty()) {
            builder.append("\n如果想调整节奏，也可以直接告诉我哪一段想换掉或留空。");
        } else {
            builder.append("当前候选里没有值得硬排进计划的组合，我先不为了凑满行程随便塞活动。")
                    .append("如果你愿意，可以放宽某个时段的条件，我再从那一段开始补。");
        }

        if (recommendResult.needDisclaimer()) {
            builder.append("\n这些建议仅供周末娱乐参考，具体安排请根据实际情况调整。");
        }
        return builder.toString();
    }

    /** 规划卡片始终以 Agent 合法选中的原始 ActivityItem 为事实来源。 */
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

    private record AgentDecision(boolean skip, ActivityItem activity, String reason) {
        static AgentDecision skip(String reason) {
            return new AgentDecision(true, null, reason == null ? "" : reason);
        }

        static AgentDecision select(ActivityItem activity, String reason) {
            return new AgentDecision(false, activity, reason == null ? "" : reason);
        }
    }

    private record ParsedOutput(
            List<RecommendedActivityOption> options,
            String speechText,
            List<ActivityPlanService.PlannedActivity> selectedPlans
    ) {
    }
}

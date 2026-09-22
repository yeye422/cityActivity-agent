package com.city.service.plan;

import com.city.agent.factory.AgentFactory;
import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityResponse;
import com.city.model.ActivitySessionResponse;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受约束多时段规划 Agent：Java 生成合法候选/具体场次空间，Agent 决定 SELECT/SKIP、组合与表达。
 * Java 最后验证候选归属、场次归属、重复活动和时间冲突。
 */
@Service
public class PlanResponseAgentService {

    private static final Pattern EXACT_HOURS = Pattern.compile("^(\\d+(?:\\.\\d+)?)小时$");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AgentFactory agentFactory;
    private final LlmJsonService llmJsonService;
    private final AgentTraceService agentTraceService;
    private final String modelName;

    public PlanResponseAgentService(
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
                    ResponseResult.textOnly("当前这些时间窗口里还没有找到足够合适的活动，我先不硬凑。如果你愿意，可以只放宽其中一段的活动类型、区域或其他偏好，我再帮你补一版。")
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
            return new RecommendResponseAgentService.Result(
                    recommend,
                    new ResponseResult(parsed.speechText(), toDisplayBlocks(recommend, parsed.selectedPlans()), "WAIT_USER")
            );
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
            activitySection.append("\n- period=").append(planned.period()).append(", candidates=[");
            List<ActivityItem> candidates = candidatePool(planned);
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) activitySection.append(", ");
                ActivityItem activity = candidates.get(i);
                DurationInfo duration = durationInfo(activity);
                activitySection.append("{")
                        .append("activityId=").append(activity.id())
                        .append(", name=").append(activity.name())
                        .append(", slots=").append(activity.slots())
                        .append(", availableStartTime=").append(activity.validStartTime())
                        .append(", availableEndTime=").append(activity.validEndTime())
                        .append(", expectedDurationMinutes=").append(duration.minutes())
                        .append(", durationSource=").append(duration.source())
                        .append(", matchScore=").append(activity.matchScore())
                        .append(", sessions=").append(sessionSummary(planned, activity.id()))
                        .append("}");
            }
            activitySection.append("]");
        }

        String weatherSummary = weather != null && weather.active() ? weather.summary() : "未启用天气排序";
        return """
                用户原话：%s
                数据源模式：%s
                用户共享九维条件：%s
                天气排序说明：%s
                Java 为细时间窗口生成的合法候选池：%s

                输出一个合法 JSON 对象，顶层只能包含 activityPlans 和 speechText。
                activityPlans 必须覆盖上面每一个 period，并明确 decision=SELECT 或 decision=SKIP。

                SELECT：
                - 必须输出 period、decision、activityId、reason。
                - activityId 必须来自该 period 的 candidates。
                - 如果该候选 sessions 非空，还必须输出 sessionId，并且必须来自该候选 sessions；具体场次时间是硬规划依据。
                - 如果 sessions=[]，不要编造 sessionId，此时结合 availableStartTime/availableEndTime 与 expectedDurationMinutes 做软规划。

                SKIP：
                - 输出 period、decision、reason；不要输出 activityId/sessionId，或设为 null。
                - 即使 candidates 非空，也可以为了整体质量主动留空。

                规划依据优先级：
                1. 具体场次 session.startAt/endAt：最高优先级，必须满足且不能与已选具体场次冲突。
                2. expectedDurationMinutes：活动实际/预计占用时间。
                   - durationSource=EXPLICIT 表示活动数据明确提供，可强依赖。
                   - ESTIMATED_FROM_LABEL 表示由时长标签估算，是较可靠软依据。
                   - ESTIMATED_FROM_TYPE 表示仅按活动类型给出的保守预期，只能作为弱依据。
                3. availableStartTime/availableEndTime：活动可安排窗口，不要把这个窗口长度误认为活动耗时。
                4. matchScore、九维条件、多样性、location 连贯性共同决定整体组合。

                规划目标：
                - 不要求填满所有窗口；可以只选 1 个或多个真正合适的活动。
                - 同一活动可能因为跨窗口可参加而出现在多个 period，最多只能选择一次。
                - 相关性优先；相近时再考虑活动类型多样性、区域连贯性和时间节奏。
                - 要利用活动预计耗时判断两个活动之间是否真正塞得下，不要只看它们分别落在哪个 2 小时窗口。
                - 对具体场次，优先选择时间衔接自然、剩余座位可用的 OPEN 场次。
                - 不要为了凑满行程牺牲匹配度或制造过密安排。

                强制规则：
                - 禁止跨 period 借候选、禁止编造 activityId/sessionId。
                - candidates=[] 的 period 必须 SKIP。
                - reason 和 speechText 只能使用候选、场次、用户条件和天气提供的事实；不得脑补价格、玩法、距离、开放状态。
                - 估算时长必须说成“预计/按标签估算”，不要伪装成精确事实。
                - speechText 先给整体安排，再自然解释 SELECT/SKIP；不要像系统日志一样逐字段输出。
                - 禁止输出“...”或示例占位值。
                """.formatted(userInput, sourceMode, sharedSlots, weatherSummary, activitySection);
    }

    private String sessionSummary(ActivityPlanService.PlannedActivity planned, Long activityId) {
        List<ActivitySessionResponse> sessions = sessionsFor(planned, activityId);
        if (sessions.isEmpty()) return "[]";
        return sessions.stream().map(session -> "{sessionId=" + session.sessionId()
                        + ",startAt=" + session.startAt()
                        + ",endAt=" + session.endAt()
                        + ",venueName=" + session.venueName()
                        + ",district=" + session.district()
                        + ",remainingSeats=" + session.remainingSeats()
                        + ",status=" + session.status() + "}")
                .toList().toString();
    }

    private ParsedOutput parseOutput(String content,
                                     List<ActivityPlanService.PlannedActivity> plannedActivities,
                                     SlotBundle sharedSlots) {
        JsonNode root = llmJsonService.parseObject(content);
        Map<String, ActivityPlanService.PlannedActivity> plansByPeriod = new LinkedHashMap<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) plansByPeriod.put(planned.period(), planned);

        Map<String, AgentDecision> requestedByPeriod = new LinkedHashMap<>();
        boolean invalidStructure = false;
        JsonNode plansNode = root.path("activityPlans");
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
                    ActivityItem activity = findCandidate(expected, activityId);
                    if (activity == null || "...".equals(reason)) {
                        invalidStructure = true;
                        continue;
                    }
                    List<ActivitySessionResponse> sessions = sessionsFor(expected, activityId);
                    ActivitySessionResponse selectedSession = null;
                    if (!sessions.isEmpty()) {
                        long sessionId = node.path("sessionId").asLong(0L);
                        selectedSession = sessions.stream()
                                .filter(session -> session.sessionId() != null && session.sessionId() == sessionId)
                                .findFirst().orElse(null);
                        if (selectedSession == null) {
                            invalidStructure = true;
                            continue;
                        }
                    }
                    requestedByPeriod.put(period, AgentDecision.select(activity, selectedSession, reason));
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
                selectedPlans.add(emptySelection(planned));
                unfilledReasons.put(planned.period(), normalizeSkipReason(requested.reason(), planned));
                continue;
            }

            Selection selection = requested == null
                    ? null
                    : new Selection(requested.activity(), requested.session());
            String reason = requested == null ? "" : requested.reason();

            if (selection == null
                    || selection.activity() == null
                    || usedIds.contains(selection.activity().id())
                    || hasTimeConflict(planned.period(), selection, selectedPlans)) {
                selection = fallbackSelection(planned, usedIds, selectedPlans);
                reason = "";
                if (!candidatePool(planned).isEmpty()) adjusted = true;
            }

            if (selection == null) {
                selectedPlans.add(emptySelection(planned));
                unfilledReasons.put(planned.period(), candidatePool(planned).isEmpty()
                        ? "当前候选池为空，这一段先留空更合适"
                        : "现有候选和已选安排衔接不够稳，这一段先留空更合适");
                continue;
            }

            usedIds.add(selection.activity().id());
            ActivityPlanService.PlannedActivity selectedPlan = new ActivityPlanService.PlannedActivity(
                    planned.period(), selection.activity(), planned.querySlots(), candidatePool(planned),
                    planned.sessionsByActivityId(), selection.session());
            selectedPlans.add(selectedPlan);
            if (reason.isBlank()) reason = templateReason(selectedPlan, sharedSlots);
            options.add(toOption(selection.activity(), reason, planned.querySlots()));
        }

        String speechText = root.path("speechText").asText("").trim();
        if (speechText.isBlank() || speechText.equals("...") || adjusted) {
            speechText = templateSpeech(
                    selectedPlans,
                    new RecommendResult(options, needsDisclaimer(sharedSlots)),
                    unfilledReasons);
        }
        return new ParsedOutput(options, speechText, selectedPlans);
    }

    private ActivityPlanService.PlannedActivity emptySelection(ActivityPlanService.PlannedActivity planned) {
        return new ActivityPlanService.PlannedActivity(
                planned.period(), null, planned.querySlots(), candidatePool(planned),
                planned.sessionsByActivityId(), null);
    }

    private Selection fallbackSelection(ActivityPlanService.PlannedActivity planned,
                                        Set<Long> usedIds,
                                        List<ActivityPlanService.PlannedActivity> selectedPlans) {
        for (ActivityItem activity : candidatePool(planned)) {
            if (activity.id() == null || usedIds.contains(activity.id())) continue;
            List<ActivitySessionResponse> sessions = sessionsFor(planned, activity.id());
            if (!sessions.isEmpty()) {
                for (ActivitySessionResponse session : sessions) {
                    Selection selection = new Selection(activity, session);
                    if (!hasTimeConflict(planned.period(), selection, selectedPlans)) return selection;
                }
                continue;
            }
            Selection selection = new Selection(activity, null);
            if (!hasTimeConflict(planned.period(), selection, selectedPlans)) return selection;
        }
        return null;
    }

    private boolean hasTimeConflict(String period,
                                    Selection candidate,
                                    List<ActivityPlanService.PlannedActivity> selectedPlans) {
        if (candidate == null || candidate.activity() == null || selectedPlans == null) return false;
        for (ActivityPlanService.PlannedActivity selectedPlan : selectedPlans) {
            if (selectedPlan == null || !selectedPlan.matched() || !samePlanningDay(period, selectedPlan.period())) continue;
            if (intervalsOverlap(candidate.session(), candidate.activity(),
                    selectedPlan.selectedSession(), selectedPlan.activity())) {
                return true;
            }
        }
        return false;
    }

    /** 具体场次优先作为硬时间；缺少场次时兼容旧活动级有效时段。 */
    private boolean intervalsOverlap(ActivitySessionResponse firstSession,
                                     ActivityItem firstActivity,
                                     ActivitySessionResponse secondSession,
                                     ActivityItem secondActivity) {
        if (firstSession != null && secondSession != null
                && firstSession.startAt() != null && firstSession.endAt() != null
                && secondSession.startAt() != null && secondSession.endAt() != null) {
            return firstSession.startAt().isBefore(secondSession.endAt())
                    && firstSession.endAt().isAfter(secondSession.startAt());
        }
        LocalTime firstStart = firstActivity == null ? null : firstActivity.validStartTime();
        LocalTime firstEnd = firstActivity == null ? null : firstActivity.validEndTime();
        LocalTime secondStart = secondActivity == null ? null : secondActivity.validStartTime();
        LocalTime secondEnd = secondActivity == null ? null : secondActivity.validEndTime();
        if (firstStart == null || firstEnd == null || secondStart == null || secondEnd == null) return false;
        return firstStart.isBefore(secondEnd) && firstEnd.isAfter(secondStart);
    }

    private boolean samePlanningDay(String firstPeriod, String secondPeriod) {
        return planningDayKey(firstPeriod).equals(planningDayKey(secondPeriod));
    }

    private String planningDayKey(String period) {
        if (period != null && period.contains("周六")) return "SATURDAY";
        if (period != null && period.contains("周日")) return "SUNDAY";
        return "SAME_DAY";
    }

    private List<ActivityPlanService.PlannedActivity> deterministicFallback(
            List<ActivityPlanService.PlannedActivity> plannedActivities) {
        List<ActivityPlanService.PlannedActivity> result = new ArrayList<>();
        Set<Long> usedIds = new LinkedHashSet<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            Selection selection = fallbackSelection(planned, usedIds, result);
            if (selection != null) usedIds.add(selection.activity().id());
            result.add(new ActivityPlanService.PlannedActivity(
                    planned.period(),
                    selection == null ? null : selection.activity(),
                    planned.querySlots(),
                    candidatePool(planned),
                    planned.sessionsByActivityId(),
                    selection == null ? null : selection.session()));
        }
        return result;
    }

    private ActivityItem findCandidate(ActivityPlanService.PlannedActivity planned, long activityId) {
        if (planned == null || activityId <= 0) return null;
        return candidatePool(planned).stream()
                .filter(activity -> activity.id() != null && activity.id() == activityId)
                .findFirst().orElse(null);
    }

    private List<ActivityItem> candidatePool(ActivityPlanService.PlannedActivity planned) {
        if (planned == null) return List.of();
        if (planned.candidates() != null && !planned.candidates().isEmpty()) return planned.candidates();
        return planned.activity() == null ? List.of() : List.of(planned.activity());
    }

    private List<ActivitySessionResponse> sessionsFor(ActivityPlanService.PlannedActivity planned, Long activityId) {
        if (planned == null || activityId == null || planned.sessionsByActivityId() == null) return List.of();
        return planned.sessionsByActivityId().getOrDefault(activityId, List.of());
    }

    private String normalizeSkipReason(String reason, ActivityPlanService.PlannedActivity planned) {
        if (reason != null && !reason.isBlank() && !"...".equals(reason)) return reason;
        if (candidatePool(planned).isEmpty()) return "当前没有合适候选，这一段先留空";
        return "为了整体行程质量，这一段先留空，不为了凑满硬塞活动";
    }

    private List<RecommendedActivityOption> templateOptions(
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            SlotBundle sharedSlots) {
        List<RecommendedActivityOption> options = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity planned : plannedActivities) {
            if (!planned.matched()) continue;
            options.add(toOption(planned.activity(), templateReason(planned, sharedSlots), planned.querySlots()));
        }
        return options;
    }

    private RecommendedActivityOption toOption(ActivityItem activity, String reason, SlotBundle querySlots) {
        SlotBundle displaySlots = querySlots != null ? querySlots : activity.slots();
        return new RecommendedActivityOption(
                activity.id(), activity.sourceType(), activity.name(), reason, activity.matchScore(), displaySlots);
    }

    private String templateReason(ActivityPlanService.PlannedActivity planned, SlotBundle sharedSlots) {
        StringBuilder reason = new StringBuilder(planned.activity().name());
        if (planned.selectedSession() != null && planned.selectedSession().startAt() != null) {
            reason.append("有可参加场次 ")
                    .append(planned.selectedSession().startAt().format(DATE_TIME));
            if (planned.selectedSession().endAt() != null) {
                reason.append("~").append(planned.selectedSession().endAt().toLocalTime());
            }
            reason.append("，");
        }
        DurationInfo duration = durationInfo(planned.activity());
        if (duration.source() == DurationSource.EXPLICIT) {
            reason.append("明确预计耗时约 ").append(duration.minutes()).append(" 分钟，");
        } else {
            reason.append("按").append(duration.source() == DurationSource.ESTIMATED_FROM_LABEL ? "时长标签" : "活动类型")
                    .append("估算约 ").append(duration.minutes()).append(" 分钟，");
        }
        if (sharedSlots != null && !sharedSlots.style().isEmpty()) {
            reason.append("也比较贴近你想要的").append(String.join("、", sharedSlots.style())).append("风格。");
        } else {
            reason.append("放在这个时间窗口比较合适。");
        }
        return reason.toString();
    }

    private String templateSpeech(
            List<ActivityPlanService.PlannedActivity> plannedActivities,
            RecommendResult recommendResult,
            Map<String, String> unfilledReasons) {
        List<ActivityPlanService.PlannedActivity> matchedPlans = plannedActivities.stream()
                .filter(ActivityPlanService.PlannedActivity::matched).toList();
        List<ActivityPlanService.PlannedActivity> unfilledPlans = plannedActivities.stream()
                .filter(planned -> !planned.matched()).toList();

        StringBuilder builder = new StringBuilder();
        if (!matchedPlans.isEmpty()) {
            builder.append("这版我按更细的时间窗口来组合，不强求每一段都塞满：");
            for (ActivityPlanService.PlannedActivity planned : matchedPlans) {
                String reason = recommendResult.recommendations().stream()
                        .filter(option -> option.itemId().equals(planned.activity().id()))
                        .map(RecommendedActivityOption::reason)
                        .findFirst().orElse(planned.activity().name());
                builder.append("\n- ").append(planned.period()).append("：")
                        .append(planned.activity().name()).append("（").append(reason).append("）");
            }
        }

        if (!unfilledPlans.isEmpty()) {
            if (!builder.isEmpty()) builder.append("\n");
            for (ActivityPlanService.PlannedActivity planned : unfilledPlans) {
                String reason = unfilledReasons == null
                        ? "这一段先留空更合适"
                        : unfilledReasons.getOrDefault(planned.period(), "这一段先留空更合适");
                builder.append(planned.period()).append("先不硬凑：").append(reason).append("。\n");
            }
            builder.append("如果你想把留空的窗口也补上，可以只放宽那一段的条件。");
        } else if (!matchedPlans.isEmpty()) {
            builder.append("\n如果想调松一点节奏，也可以直接告诉我哪一段想换掉或留空。");
        }
        return builder.toString().trim();
    }

    private DurationInfo durationInfo(ActivityItem activity) {
        if (activity != null && activity.durationMinutes() != null && activity.durationMinutes() > 0) {
            return new DurationInfo(activity.durationMinutes(), DurationSource.EXPLICIT);
        }
        List<String> labels = activity == null || activity.slots() == null
                ? List.of() : activity.slots().duration();
        for (String label : labels == null ? List.<String>of() : labels) {
            Integer minutes = durationMinutesFromLabel(label);
            if (minutes != null) return new DurationInfo(minutes, DurationSource.ESTIMATED_FROM_LABEL);
        }
        String type = activity == null || activity.slots() == null || activity.slots().activityType().isEmpty()
                ? "" : activity.slots().activityType().getFirst();
        int estimated = switch (type) {
            case "电影" -> 120;
            case "展览" -> 120;
            case "演出" -> 120;
            case "桌游" -> 150;
            case "运动" -> 120;
            case "探店" -> 90;
            default -> 120;
        };
        return new DurationInfo(estimated, DurationSource.ESTIMATED_FROM_TYPE);
    }

    private Integer durationMinutesFromLabel(String label) {
        if (label == null || label.isBlank()) return null;
        return switch (label.trim()) {
            case "1小时内" -> 60;
            case "1-2小时" -> 90;
            case "2-4小时" -> 180;
            case "半天" -> 240;
            case "全天" -> 480;
            default -> {
                Matcher matcher = EXACT_HOURS.matcher(label.trim());
                if (matcher.matches()) {
                    yield (int) Math.round(Double.parseDouble(matcher.group(1)) * 60);
                }
                yield null;
            }
        };
    }

    private List<ActivityResponse> toDisplayBlocks(
            RecommendResult recommendResult,
            List<ActivityPlanService.PlannedActivity> plans) {
        if (recommendResult == null || recommendResult.recommendations() == null
                || plans == null || plans.isEmpty()) return List.of();
        Map<Long, ActivityItem> byId = new LinkedHashMap<>();
        for (ActivityPlanService.PlannedActivity planned : plans) {
            if (planned != null && planned.matched()) byId.putIfAbsent(planned.activity().id(), planned.activity());
        }
        return recommendResult.recommendations().stream()
                .map(option -> option == null ? null : byId.get(option.itemId()))
                .filter(activity -> activity != null)
                .map(ActivityResponse::from)
                .toList();
    }

    private boolean needsDisclaimer(SlotBundle slots) {
        return false;
    }

    private record Selection(ActivityItem activity, ActivitySessionResponse session) {}

    private record AgentDecision(boolean skip,
                                 ActivityItem activity,
                                 ActivitySessionResponse session,
                                 String reason) {
        static AgentDecision skip(String reason) {
            return new AgentDecision(true, null, null, reason == null ? "" : reason);
        }

        static AgentDecision select(ActivityItem activity, ActivitySessionResponse session, String reason) {
            return new AgentDecision(false, activity, session, reason == null ? "" : reason);
        }
    }

    private record ParsedOutput(
            List<RecommendedActivityOption> options,
            String speechText,
            List<ActivityPlanService.PlannedActivity> selectedPlans
    ) {}

    private record DurationInfo(int minutes, DurationSource source) {}

    private enum DurationSource {
        EXPLICIT,
        ESTIMATED_FROM_LABEL,
        ESTIMATED_FROM_TYPE
    }
}

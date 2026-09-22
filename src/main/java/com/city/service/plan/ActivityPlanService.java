package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivitySearchRequest;
import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.AgentResult;
import com.city.model.agent.EvidenceRef;
import com.city.model.agent.PlanningResult;
import com.city.model.retrieval.RetrievalRequest;
import com.city.model.retrieval.RetrievalResult;
import com.city.service.agent.EvidenceRefFactory;
import com.city.service.activity.ActivityDiversityService;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import com.city.service.activity.ActivitySessionService;
import com.city.service.retrieval.RetrievalPipeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多时段规划候选发现服务。
 *
 * <p>窗口内候选统一通过 RetrievalPipeline 完成硬过滤、排序和多样性控制；
 * 本服务只补充具体 OPEN 场次和规划证据，最终组合合法性仍由 PlanningSolver 判定。</p>
 */
@Service
public class ActivityPlanService {

    private static final List<PlanWindow> PLAN_WINDOWS = List.of(
            new PlanWindow("08:00-10:00", LocalTime.of(8, 0), LocalTime.of(10, 0)),
            new PlanWindow("10:00-12:00", LocalTime.of(10, 0), LocalTime.of(12, 0)),
            new PlanWindow("12:00-14:00", LocalTime.of(12, 0), LocalTime.of(14, 0)),
            new PlanWindow("14:00-16:00", LocalTime.of(14, 0), LocalTime.of(16, 0)),
            new PlanWindow("16:00-18:00", LocalTime.of(16, 0), LocalTime.of(18, 0)),
            new PlanWindow("18:00-20:00", LocalTime.of(18, 0), LocalTime.of(20, 0)),
            new PlanWindow("20:00-23:00", LocalTime.of(20, 0), LocalTime.of(23, 0))
    );

    private static final int PLAN_CANDIDATE_LIMIT = 3;

    private final RetrievalPipeline retrievalPipeline;
    private final ActivitySessionService activitySessionService;
    private final EvidenceRefFactory evidenceRefFactory = new EvidenceRefFactory();

    @Autowired
    public ActivityPlanService(RetrievalPipeline retrievalPipeline,
                               ActivitySessionService activitySessionService) {
        this.retrievalPipeline = retrievalPipeline;
        this.activitySessionService = activitySessionService;
    }

    /** 兼容已有纯单测；生产运行时直接注入统一 RetrievalPipeline。 */
    public ActivityPlanService(ActivitySearchService activitySearchService,
                               ActivityRankService activityRankService,
                               ActivitySessionService activitySessionService) {
        this(new RetrievalPipeline(
                        activitySearchService,
                        activityRankService,
                        new ActivityDiversityService()),
                activitySessionService);
    }

    /** 旧调用兼容；最终窗口解析已迁到 TimeWindowResolver。 */
    public List<String> resolveActivityTimes(SlotBundle slots, TimeConstraint timeConstraint) {
        return defaultActivityTimes(timeConstraint);
    }

    private List<String> defaultActivityTimes(TimeConstraint timeConstraint) {
        List<PlanWindow> windows;
        if (timeConstraint != null && timeConstraint.hasTime()) {
            windows = windowsCoveredByTimeRange(timeConstraint.startTime(), timeConstraint.endTime());
            if (windows.isEmpty()) windows = PLAN_WINDOWS;
        } else {
            windows = PLAN_WINDOWS;
        }
        return decorateWindowsWithDate(windows, timeConstraint);
    }

    private List<PlanWindow> windowsCoveredByTimeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) return List.of();
        return PLAN_WINDOWS.stream()
                .filter(window -> overlaps(start, end, window.start(), window.end()))
                .toList();
    }

    private boolean overlaps(LocalTime start, LocalTime end, LocalTime periodStart, LocalTime periodEnd) {
        return start.isBefore(periodEnd) && end.isAfter(periodStart);
    }

    private List<String> decorateWindowsWithDate(List<PlanWindow> windows, TimeConstraint timeConstraint) {
        String prefix = "";
        if (timeConstraint != null && timeConstraint.hasDate() && timeConstraint.dateStart() != null) {
            prefix = switch (timeConstraint.dateStart().getDayOfWeek()) {
                case SATURDAY -> "周六 ";
                case SUNDAY -> "周日 ";
                default -> "";
            };
        } else if (timeConstraint == null || !timeConstraint.hasDate()) {
            prefix = "周六 ";
        }
        String safePrefix = prefix;
        return windows.stream().map(window -> safePrefix + window.label()).toList();
    }

    public SlotBundle slotsForActivityTime(SlotBundle base, String activityTime) {
        SlotBundle safe = base == null ? SlotBundle.empty() : base;
        return new SlotBundle(
                safe.city(), safe.location(), safe.experienceGoal(), safe.companion(), safe.budget(),
                safe.activityType(), safe.style(), safe.duration(), safe.feature());
    }

    public List<PlannedActivity> planActivities(SourceMode sourceMode,
                                                 Long userId,
                                                 SlotBundle baseSlots,
                                                 SlotBundle excludedSlots,
                                                 List<String> activityTimes,
                                                 TimeConstraint timeConstraint,
                                                 WeatherRecommendationContext weather) {
        List<String> targets = activityTimes == null || activityTimes.isEmpty()
                ? defaultActivityTimes(timeConstraint)
                : activityTimes;
        SlotBundle safeExcluded = excludedSlots == null ? SlotBundle.empty() : excludedSlots;
        WeatherRecommendationContext safeWeather = weather == null
                ? WeatherRecommendationContext.inactive()
                : weather;
        List<PlannedActivity> discovered = (targets.size() >= 3
                ? targets.parallelStream()
                : targets.stream())
                .map(activityTime -> discoverWindow(
                        sourceMode, userId, baseSlots, safeExcluded,
                        activityTime, timeConstraint, safeWeather))
                .toList();

        List<PlannedActivity> planned = new ArrayList<>();
        Set<Long> fallbackUsedIds = new LinkedHashSet<>();
        for (PlannedActivity window : discovered) {
            ActivityItem fallback = window.candidates().stream()
                    .filter(item -> !fallbackUsedIds.contains(item.id()))
                    .findFirst()
                    .orElse(null);
            if (fallback != null) fallbackUsedIds.add(fallback.id());
            planned.add(new PlannedActivity(
                    window.period(), fallback, window.querySlots(), window.candidates(),
                    window.sessionsByActivityId(), firstSessionFor(fallback, window.sessionsByActivityId())));
        }
        return List.copyOf(planned);
    }

    private PlannedActivity discoverWindow(SourceMode sourceMode,
                                           Long userId,
                                           SlotBundle baseSlots,
                                           SlotBundle excludedSlots,
                                           String activityTime,
                                           TimeConstraint timeConstraint,
                                           WeatherRecommendationContext weather) {
        SlotBundle querySlots = slotsForActivityTime(baseSlots, activityTime);
        TimeConstraint targetTimeConstraint = timeConstraintForActivityTime(timeConstraint, activityTime);
        ActivitySearchRequest request = new ActivitySearchRequest(
                sourceMode, userId, querySlots, List.of(), targetTimeConstraint, excludedSlots);
        RetrievalResult retrieval = retrievalPipeline.retrieve(new RetrievalRequest(
                request,
                "",
                weather,
                PLAN_CANDIDATE_LIMIT));
        List<ActivityItem> topCandidates = retrieval.finalCandidates();
        Map<Long, List<ActivitySessionResponse>> sessionsByActivityId = loadPlanningSessions(
                topCandidates, targetTimeConstraint);
        return new PlannedActivity(
                activityTime, null, querySlots, topCandidates, sessionsByActivityId, null);
    }

    public PlanningResult planWithEvidence(SourceMode sourceMode,
                                           Long userId,
                                           SlotBundle baseSlots,
                                           SlotBundle excludedSlots,
                                           List<String> activityTimes,
                                           TimeConstraint timeConstraint,
                                           WeatherRecommendationContext weather) {
        List<PlannedActivity> plans = planActivities(
                sourceMode, userId, baseSlots, excludedSlots, activityTimes, timeConstraint, weather);
        Map<Long, ActivityItem> activities = new LinkedHashMap<>();
        Map<Long, ActivitySessionResponse> sessions = new LinkedHashMap<>();
        for (PlannedActivity plan : plans) {
            for (ActivityItem item : plan.candidates()) {
                if (item != null && item.id() != null) activities.putIfAbsent(item.id(), item);
            }
            plan.sessionsByActivityId().values().stream().flatMap(List::stream)
                    .filter(session -> session != null && session.sessionId() != null)
                    .forEach(session -> sessions.putIfAbsent(session.sessionId(), session));
        }
        List<EvidenceRef> evidence = new ArrayList<>();
        activities.values().stream().map(evidenceRefFactory::activity).forEach(evidence::add);
        sessions.values().stream().map(evidenceRefFactory::session).forEach(evidence::add);
        sessions.values().stream()
                .filter(session -> session.venueId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        ActivitySessionResponse::venueId,
                        session -> session,
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values().stream().map(evidenceRefFactory::venue).forEach(evidence::add);
        if (weather != null && weather.status() != WeatherRecommendationContext.Status.NOT_REQUESTED) {
            evidence.add(evidenceRefFactory.weather(weather, baseSlots, timeConstraint));
        }
        AgentResult result = new AgentResult(
                AgentResult.Status.COMPLETED,
                "已验证规划窗口 " + plans.size() + " 个",
                Set.copyOf(activities.keySet()),
                Set.copyOf(sessions.keySet()),
                evidence,
                List.of(),
                Map.of("windowCount", plans.size(), "activityCount", activities.size(),
                        "sessionCount", sessions.size()));
        return new PlanningResult(plans, result);
    }

    private Map<Long, List<ActivitySessionResponse>> loadPlanningSessions(
            List<ActivityItem> candidates,
            TimeConstraint targetTimeConstraint) {
        if (activitySessionService == null
                || candidates == null || candidates.isEmpty()
                || targetTimeConstraint == null || !targetTimeConstraint.hasDate()
                || targetTimeConstraint.dateStart() == null) {
            return Map.of();
        }

        LocalDate date = targetTimeConstraint.dateStart();
        LocalTime windowStart = targetTimeConstraint.startTime();
        LocalTime windowEnd = targetTimeConstraint.endTime();
        Map<Long, List<ActivitySessionResponse>> result = new LinkedHashMap<>();

        for (ActivityItem candidate : candidates) {
            if (candidate == null || candidate.id() == null) continue;
            List<ActivitySessionResponse> sessions;
            try {
                sessions = activitySessionService.findAvailable(candidate.id(), date).stream()
                        .filter(session -> "OPEN".equalsIgnoreCase(session.status()))
                        .filter(session -> session.remainingSeats() == null || session.remainingSeats() > 0)
                        .filter(session -> sessionOverlapsWindow(session, windowStart, windowEnd))
                        .toList();
            } catch (Exception ignored) {
                sessions = List.of();
            }
            if (!sessions.isEmpty()) result.put(candidate.id(), sessions);
        }
        return Map.copyOf(result);
    }

    private boolean sessionOverlapsWindow(ActivitySessionResponse session, LocalTime start, LocalTime end) {
        if (session == null || session.startAt() == null || session.endAt() == null) return false;
        if (start == null || end == null) return true;
        LocalDateTime windowStart = session.startAt().toLocalDate().atTime(start);
        LocalDateTime windowEnd = session.startAt().toLocalDate().atTime(end);
        return session.startAt().isBefore(windowEnd) && session.endAt().isAfter(windowStart);
    }

    private ActivitySessionResponse firstSessionFor(
            ActivityItem activity,
            Map<Long, List<ActivitySessionResponse>> sessionsByActivityId) {
        if (activity == null || activity.id() == null || sessionsByActivityId == null) return null;
        List<ActivitySessionResponse> sessions = sessionsByActivityId.getOrDefault(activity.id(), List.of());
        return sessions.isEmpty() ? null : sessions.getFirst();
    }

    private TimeConstraint timeConstraintForActivityTime(TimeConstraint original, String activityTime) {
        LocalDate date = original != null && original.hasDate()
                ? dateForActivityTime(original.dateStart(), original.dateEnd(), activityTime)
                : null;
        PlanWindow window = findWindow(activityTime);
        if (window != null) {
            return new TimeConstraint(
                    original == null ? activityTime : original.raw(),
                    date, date, window.start(), window.end(),
                    original == null ? null : original.resolvedAt());
        }

        LocalTime startTime = original != null ? original.startTime() : null;
        LocalTime endTime = original != null ? original.endTime() : null;
        if (activityTime != null && activityTime.contains("上午")) {
            startTime = LocalTime.of(8, 0); endTime = LocalTime.of(12, 0);
        } else if (activityTime != null && activityTime.contains("下午")) {
            startTime = LocalTime.of(12, 0); endTime = LocalTime.of(18, 0);
        } else if (activityTime != null && activityTime.contains("晚上")) {
            startTime = LocalTime.of(18, 0); endTime = LocalTime.of(23, 0);
        }
        return new TimeConstraint(
                original == null ? activityTime : original.raw(),
                date, date, startTime, endTime,
                original == null ? null : original.resolvedAt());
    }

    private PlanWindow findWindow(String activityTime) {
        if (activityTime == null || activityTime.isBlank()) return null;
        return PLAN_WINDOWS.stream()
                .filter(window -> activityTime.endsWith(window.label()))
                .findFirst()
                .orElse(null);
    }

    private LocalDate dateForActivityTime(LocalDate start, LocalDate end, String activityTime) {
        if (start == null) return null;
        if (activityTime == null || !activityTime.startsWith("周日") || end == null) return start;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) return date;
        }
        return start;
    }

    private record PlanWindow(String label, LocalTime start, LocalTime end) { }

    public record PlannedActivity(
            String period,
            ActivityItem activity,
            SlotBundle querySlots,
            List<ActivityItem> candidates,
            Map<Long, List<ActivitySessionResponse>> sessionsByActivityId,
            ActivitySessionResponse selectedSession
    ) {
        public PlannedActivity {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            sessionsByActivityId = sessionsByActivityId == null ? Map.of() : Map.copyOf(sessionsByActivityId);
        }

        public PlannedActivity(String period,
                               ActivityItem activity,
                               SlotBundle querySlots,
                               List<ActivityItem> candidates) {
            this(period, activity, querySlots, candidates, Map.of(), null);
        }

        public PlannedActivity(String period, ActivityItem activity, SlotBundle querySlots) {
            this(period, activity, querySlots,
                    activity == null ? List.of() : List.of(activity), Map.of(), null);
        }

        public boolean matched() {
            return activity != null && activity.id() != null;
        }
    }
}

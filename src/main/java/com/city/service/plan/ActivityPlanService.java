package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivitySearchRequest;
import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.AgentResult;
import com.city.model.agent.EvidenceRef;
import com.city.model.agent.PlanningResult;
import com.city.service.agent.EvidenceRefFactory;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import com.city.service.activity.ActivitySessionService;
import com.city.service.worker.RetrievalWorker;
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
 * 多时段规划服务：把用户可用时间拆成细粒度候选发现窗口，
 * 再按窗口独立检索/排序后保留 TopK 候选。
 * Java 负责合法候选空间；真正选几个、选哪些、哪些窗口留空由 PlanResponseAgent 决定。
 */
@Service
public class ActivityPlanService {

    /** 细粒度候选发现窗口；窗口不是活动耗时。 */
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

    private final RetrievalWorker retrievalWorker;
    private final ActivityRankService activityRankService;
    private final ActivitySessionService activitySessionService;
    private final EvidenceRefFactory evidenceRefFactory = new EvidenceRefFactory();

    @Autowired
    public ActivityPlanService(RetrievalWorker retrievalWorker,
                               ActivityRankService activityRankService,
                               ActivitySessionService activitySessionService) {
        this.retrievalWorker = retrievalWorker;
        this.activityRankService = activityRankService;
        this.activitySessionService = activitySessionService;
    }

    /**
     * 保留测试和旧调用方的兼容构造方式；生产运行时由 Spring 注入统一 RetrievalWorker。
     */
    public ActivityPlanService(ActivitySearchService activitySearchService,
                               ActivityRankService activityRankService,
                               ActivitySessionService activitySessionService) {
        this(new RetrievalWorker(activitySearchService), activityRankService, activitySessionService);
    }

    /**
     * 例如 12:00~23:00 会拆成 12-14、14-16、16-18、18-20、20-23，
     * 给 PlanResponseAgent 更大的合法组合空间。
     */
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

    /** 复制完整九维共享槽位；规划窗口通过 TimeConstraint 单独处理。 */
    public SlotBundle slotsForActivityTime(SlotBundle base, String activityTime) {
        SlotBundle safe = base == null ? SlotBundle.empty() : base;
        return new SlotBundle(
                safe.city(),
                safe.location(),
                safe.experienceGoal(),
                safe.companion(),
                safe.budget(),
                safe.activityType(),
                safe.style(),
                safe.duration(),
                safe.feature()
        );
    }

    /**
     * 按细窗口独立检索、排序并保留 Top3 候选。
     * 有明确日期时，同时加载候选在该窗口内的具体 OPEN 场次，交给 PlanResponseAgent 参与组合。
     */
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
        // 只有三个及以上窗口才并行，避免简单任务承担线程切换成本；有序流保证结果仍按窗口顺序返回。
        List<PlannedActivity> discovered = (targets.size() >= 3
                ? targets.parallelStream()
                : targets.stream())
                .map(activityTime -> discoverWindow(
                        sourceMode, userId, baseSlots, safeExcluded,
                        activityTime, timeConstraint, safeWeather))
                .toList();

        // 并行检索结束后由 Java 单线程统一选择 fallback，保持去重和确定性。
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
        List<ActivityItem> candidates = retrievalWorker.retrieveCandidates(new ActivitySearchRequest(
                sourceMode, userId, querySlots, List.of(), targetTimeConstraint, excludedSlots));
        List<ActivityItem> topCandidates = activityRankService.rank(
                        new ActivityRankRequest(candidates, querySlots, targetTimeConstraint, List.of()), weather)
                .ranked().stream()
                .filter(item -> item != null && item.id() != null)
                .limit(PLAN_CANDIDATE_LIMIT)
                .toList();
        Map<Long, List<ActivitySessionResponse>> sessionsByActivityId = loadPlanningSessions(
                topCandidates, targetTimeConstraint);
        return new PlannedActivity(
                activityTime, null, querySlots, topCandidates, sessionsByActivityId, null);
    }

    /** 规划候选、场次、场地和天气证据作为一个结构化 Worker 结果返回。 */
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

    /** 只有日期明确时，具体场次才具有可执行含义；没有日期时不向 Agent 虚构/泛化场次。 */
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

    private record PlanWindow(String label, LocalTime start, LocalTime end) {}

    /**
     * 单窗口规划结果：
     * candidates 是合法 TopK；sessionsByActivityId 是具体可参加场次；selectedSession 是 Java fallback/最终选择使用的场次。
     */
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

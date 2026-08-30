package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivitySearchRequest;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多时段规划服务：把用户可用时间拆成较细的候选发现窗口，
 * 再按窗口独立检索/排序后保留 TopK 候选。
 * Java 负责合法候选空间；真正选几个、选哪些、哪些窗口留空由 PlanResponseAgent 决定。
 */
@Service
public class ActivityPlanService {

    /**
     * 细粒度候选发现窗口。窗口只是召回锚点，不代表活动本身只能占用这两个小时；
     * Agent 会同时看到活动真实有效时段和明确/预计活动耗时。
     */
    private static final List<PlanWindow> PLAN_WINDOWS = List.of(
            new PlanWindow("08:00-10:00", LocalTime.of(8, 0), LocalTime.of(10, 0)),
            new PlanWindow("10:00-12:00", LocalTime.of(10, 0), LocalTime.of(12, 0)),
            new PlanWindow("12:00-14:00", LocalTime.of(12, 0), LocalTime.of(14, 0)),
            new PlanWindow("14:00-16:00", LocalTime.of(14, 0), LocalTime.of(16, 0)),
            new PlanWindow("16:00-18:00", LocalTime.of(16, 0), LocalTime.of(18, 0)),
            new PlanWindow("18:00-20:00", LocalTime.of(18, 0), LocalTime.of(20, 0)),
            new PlanWindow("20:00-23:00", LocalTime.of(20, 0), LocalTime.of(23, 0))
    );

    /** 个人项目保持小候选空间：每个细窗口 Top3。 */
    private static final int PLAN_CANDIDATE_LIMIT = 3;

    private final ActivitySearchService activitySearchService;
    private final ActivityRankService activityRankService;

    public ActivityPlanService(ActivitySearchService activitySearchService, ActivityRankService activityRankService) {
        this.activitySearchService = activitySearchService;
        this.activityRankService = activityRankService;
    }

    /**
     * 从规范化 TimeConstraint 推导候选发现窗口。
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
            if (windows.isEmpty()) {
                windows = PLAN_WINDOWS;
            }
        } else {
            windows = PLAN_WINDOWS;
        }
        return decorateWindowsWithDate(windows, timeConstraint);
    }

    /** 按真实时间范围挑出所有发生重叠的细粒度窗口，区间按 [start,end) 处理。 */
    private List<PlanWindow> windowsCoveredByTimeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            return List.of();
        }
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
            // 沿用城市周末规划的默认语义：没有日期时默认按周六生成候选空间。
            prefix = "周六 ";
        }
        String safePrefix = prefix;
        return windows.stream().map(window -> safePrefix + window.label()).toList();
    }

    /** 复制共享正向槽位；规划窗口通过 TimeConstraint 处理，不写回 SlotBundle。 */
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
                safe.duration()
        );
    }

    /**
     * 按细窗口独立检索、排序并保留 Top3 候选。
     * 同一个跨窗口活动可能出现在多个候选池中，这是有意保留的组合空间；
     * 最终重复 ID、时间冲突由 PlanResponseAgentService 的 Java 校验层处理。
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
        List<PlannedActivity> planned = new ArrayList<>();
        Set<Long> fallbackUsedIds = new LinkedHashSet<>();

        for (String activityTime : targets) {
            SlotBundle querySlots = slotsForActivityTime(baseSlots, activityTime);
            TimeConstraint targetTimeConstraint = timeConstraintForActivityTime(timeConstraint, activityTime);

            List<ActivityItem> candidates = activitySearchService.search(
                    new ActivitySearchRequest(
                            sourceMode,
                            userId,
                            querySlots,
                            List.of(),
                            targetTimeConstraint,
                            safeExcluded
                    ));
            List<ActivityItem> ranked = activityRankService.rank(
                            new ActivityRankRequest(candidates, querySlots, targetTimeConstraint, List.of()),
                            safeWeather)
                    .ranked();
            List<ActivityItem> topCandidates = ranked.stream()
                    .filter(item -> item != null && item.id() != null)
                    .limit(PLAN_CANDIDATE_LIMIT)
                    .toList();

            ActivityItem fallback = topCandidates.stream()
                    .filter(item -> !fallbackUsedIds.contains(item.id()))
                    .findFirst()
                    .orElse(null);
            if (fallback != null) {
                fallbackUsedIds.add(fallback.id());
            }
            planned.add(new PlannedActivity(activityTime, fallback, querySlots, topCandidates));
        }
        return planned;
    }

    /** 为一个细粒度候选发现窗口生成对应检索时间范围。 */
    private TimeConstraint timeConstraintForActivityTime(TimeConstraint original, String activityTime) {
        LocalDate date = original != null && original.hasDate()
                ? dateForActivityTime(original.dateStart(), original.dateEnd(), activityTime)
                : null;
        PlanWindow window = findWindow(activityTime);
        if (window != null) {
            return new TimeConstraint(
                    original == null ? activityTime : original.raw(),
                    date,
                    date,
                    window.start(),
                    window.end(),
                    original == null ? null : original.resolvedAt()
            );
        }

        // 兼容旧测试/旧调用传入“上午/下午/晚上”。
        LocalTime startTime = original != null ? original.startTime() : null;
        LocalTime endTime = original != null ? original.endTime() : null;
        if (activityTime != null && activityTime.contains("上午")) {
            startTime = LocalTime.of(8, 0);
            endTime = LocalTime.of(12, 0);
        } else if (activityTime != null && activityTime.contains("下午")) {
            startTime = LocalTime.of(12, 0);
            endTime = LocalTime.of(18, 0);
        } else if (activityTime != null && activityTime.contains("晚上")) {
            startTime = LocalTime.of(18, 0);
            endTime = LocalTime.of(23, 0);
        }
        return new TimeConstraint(
                original == null ? activityTime : original.raw(),
                date,
                date,
                startTime,
                endTime,
                original == null ? null : original.resolvedAt()
        );
    }

    private PlanWindow findWindow(String activityTime) {
        if (activityTime == null || activityTime.isBlank()) {
            return null;
        }
        return PLAN_WINDOWS.stream()
                .filter(window -> activityTime.endsWith(window.label()))
                .findFirst()
                .orElse(null);
    }

    /** 周日标签命中日期范围内的周日；其他窗口默认使用范围首日。 */
    private LocalDate dateForActivityTime(LocalDate start, LocalDate end, String activityTime) {
        if (start == null) return null;
        if (activityTime == null || !activityTime.startsWith("周日") || end == null) return start;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) return date;
        }
        return start;
    }

    private record PlanWindow(String label, LocalTime start, LocalTime end) {
    }

    /**
     * 单窗口规划结果：period = 候选发现窗口；activity = Java fallback；
     * candidates = 交给 PlanResponseAgent 做全局组合选择的 TopK 候选。
     */
    public record PlannedActivity(
            String period,
            ActivityItem activity,
            SlotBundle querySlots,
            List<ActivityItem> candidates
    ) {
        public PlannedActivity {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /** 保留旧调用兼容：已选活动同时作为唯一候选。 */
        public PlannedActivity(String period, ActivityItem activity, SlotBundle querySlots) {
            this(period, activity, querySlots, activity == null ? List.of() : List.of(activity));
        }

        public boolean matched() {
            return activity != null && activity.id() != null;
        }
    }
}

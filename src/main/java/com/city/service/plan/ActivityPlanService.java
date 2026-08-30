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
 * 多时段规划服务：解析目标活动时段，并按时段独立检索/排序后保留 TopK 候选。
 * Java 仍会给出一个确定性 Top1 组合作为 Agent 失败时的 fallback；真正的全局组合选择由 PlanResponseAgent 完成。
 */
@Service
public class ActivityPlanService {

    /** 一天规划默认拆分的日内时段。日期由 TimeConstraint 单独确定。 */
    private static final List<String> DEFAULT_DAY_PERIODS = List.of("上午", "下午", "晚上");
    /** 个人项目保持小候选空间：三个时段各 Top3，最多 27 种组合。 */
    private static final int PLAN_CANDIDATE_LIMIT = 3;

    private final ActivitySearchService activitySearchService;
    private final ActivityRankService activityRankService;

    public ActivityPlanService(ActivitySearchService activitySearchService, ActivityRankService activityRankService) {
        this.activitySearchService = activitySearchService;
        this.activityRankService = activityRankService;
    }

    /**
     * 从规范化 TimeConstraint 推导一天内的规划时段。
     * 周六/周日保留星期标签；其他具体日期使用上午/下午/晚上。
     */
    public List<String> resolveActivityTimes(SlotBundle slots, TimeConstraint timeConstraint) {
        return defaultActivityTimes(timeConstraint);
    }

    private List<String> defaultActivityTimes(TimeConstraint timeConstraint) {
        if (timeConstraint != null && timeConstraint.hasTime() && !timeConstraint.hasDate()) {
            LocalTime start = timeConstraint.startTime();
            if (!start.isBefore(LocalTime.of(12, 0)) && start.isBefore(LocalTime.of(18, 0))) {
                return List.of("下午");
            }
            if (!start.isBefore(LocalTime.of(18, 0))) {
                return List.of("晚上");
            }
            return List.of("上午");
        }
        if (timeConstraint == null || !timeConstraint.hasDate()) {
            return List.of("周六上午", "周六下午", "周六晚上");
        }
        return switch (timeConstraint.dateStart().getDayOfWeek()) {
            case SATURDAY -> List.of("周六上午", "周六下午", "周六晚上");
            case SUNDAY -> List.of("周日上午", "周日下午", "周日晚上");
            default -> DEFAULT_DAY_PERIODS;
        };
    }

    /** 复制共享正向槽位；规划时段通过 TimeConstraint 处理，不写回 SlotBundle。 */
    public SlotBundle slotsForActivityTime(SlotBundle base, String activityTime) {
        SlotBundle safe = base == null ? SlotBundle.empty() : base;
        return new SlotBundle(
                safe.city(),
                safe.location(),
                safe.mood(),
                safe.scene(),
                safe.budget(),
                safe.activityType(),
                safe.style(),
                safe.duration()
        );
    }

    /**
     * 按时段独立检索、排序并保留 Top3 候选。
     * 候选生成阶段不提前跨时段排除 activityId，因为全局组合尚未发生；
     * 同时按原 Rank 顺序构造一份不重复的确定性 Top1 结果，供 Agent 失败或非法输出时 fallback。
     * excludedSlots 始终作为硬排除条件透传；WeatherContext 与普通推荐共用同一套天气排序语义。
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

    /** 为上午/下午/晚上生成对应的场次过滤窗口。 */
    private TimeConstraint timeConstraintForActivityTime(TimeConstraint original, String activityTime) {
        if (original == null || !original.hasConstraint()) return TimeConstraint.empty();

        LocalDate date = original.hasDate()
                ? dateForActivityTime(original.dateStart(), original.dateEnd(), activityTime)
                : null;
        LocalTime startTime = original.startTime();
        LocalTime endTime = original.endTime();
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
        return new TimeConstraint(original.raw(), date, date, startTime, endTime, original.resolvedAt());
    }

    /** 周日标签命中日期范围内的周日；其他默认规划时段使用范围首日（周末即周六）。 */
    private LocalDate dateForActivityTime(LocalDate start, LocalDate end, String activityTime) {
        if (start == null) return null;
        if (activityTime == null || !activityTime.startsWith("周日") || end == null) return start;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) return date;
        }
        return start;
    }

    /**
     * 单时段规划结果：
     * period = 时段；activity = Java 的确定性 fallback 选择；querySlots = 该时段查询条件；
     * candidates = 交给 PlanResponseAgent 做全局组合选择的已排序 TopK 候选。
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

        /** 保留旧调用兼容：测试或旧代码手工构造时，已选活动同时作为唯一候选。 */
        public PlannedActivity(String period, ActivityItem activity, SlotBundle querySlots) {
            this(period, activity, querySlots, activity == null ? List.of() : List.of(activity));
        }

        public boolean matched() {
            return activity != null && activity.id() != null;
        }
    }
}

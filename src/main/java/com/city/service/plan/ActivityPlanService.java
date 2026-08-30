package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivitySearchRequest;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 多时段规划服务：解析目标活动时段，并按时段拆分检索/重排后各取一款。
 */
@Service
public class ActivityPlanService {

    /** 一天规划默认拆分的日内时段。日期由 TimeConstraint 单独确定。 */
    private static final List<String> DEFAULT_DAY_PERIODS = List.of("上午", "下午", "晚上");

    /** 「周末」聚合标签，需展开为具体活动时段。 */
    private static final String AGGREGATE_WEEKEND = "周末";

    private final ActivitySearchService activitySearchService;
    private final ActivityRankService activityRankService;

    public ActivityPlanService(ActivitySearchService activitySearchService, ActivityRankService activityRankService) {
        this.activitySearchService = activitySearchService;
        this.activityRankService = activityRankService;
    }

    /**
     * 从合并后的槽位解析规划时段。
     *   含「周末」或为空 → 默认周六上午/下午/晚上
     *   含 ≥2 个具体时段 → 按用户指定顺序去重保留
     *   仅 1 个具体时段 → 仍按默认多时段规划
     */
    public List<String> resolveActivityTimes(SlotBundle slots) {
        return resolveActivityTimes(slots, TimeConstraint.empty());
    }

    /**
     * 解析一天内的规划时段。对周六、周日保留旧活动库使用的星期标签；其他具体日期只保留
     * “上午/下午/晚上”语义，改由日期和营业时间字段过滤，避免把任意日期误当成周六。
     */
    public List<String> resolveActivityTimes(SlotBundle slots, TimeConstraint timeConstraint) {
        // 规划时段不再读取 SlotBundle.activityTime，统一由用户 TimeConstraint 推导。
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

    /**
     * 复制共享槽位；规划时段通过 TimeConstraint 处理，不写入活动槽位。
     */
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
     * 按时段依次检索重排，每个时段取 top1；跨时段排除已选 activityId，避免重复活动。
     */
    public List<PlannedActivity> planActivities(SourceMode sourceMode, Long userId, SlotBundle baseSlots, List<String> activityTimes) {
        return planActivities(sourceMode, userId, baseSlots, activityTimes, TimeConstraint.empty());
    }

    /**
     * 规划场景按“日内时段”拆分检索。
     * <p>
     * 日期约束用于限定每个规划时段发生在哪一天。
     */
    public List<PlannedActivity> planActivities(SourceMode sourceMode, Long userId, SlotBundle baseSlots,
                                                 List<String> activityTimes, TimeConstraint timeConstraint) {
        List<String> targets = activityTimes == null || activityTimes.isEmpty()
                ? defaultActivityTimes(timeConstraint)
                : activityTimes;
        List<PlannedActivity> planned = new ArrayList<>();
        Set<Long> usedIds = new LinkedHashSet<>();

        for (String activityTime : targets) {
            // 时段仅用于生成展示窗口；活动可用性完全由 activity_session 过滤。
            SlotBundle querySlots = slotsForActivityTime(baseSlots, activityTime);
            TimeConstraint targetTimeConstraint = timeConstraintForActivityTime(timeConstraint, activityTime);
            List<Long> excludeIds = List.copyOf(usedIds);
            List<ActivityItem> candidates = activitySearchService.search(
                    new ActivitySearchRequest(sourceMode, userId, querySlots, excludeIds, targetTimeConstraint));
            List<ActivityItem> ranked = activityRankService.rank(
                    new ActivityRankRequest(candidates, querySlots, targetTimeConstraint, excludeIds))
                    .ranked();
            ActivityItem picked = ranked.stream()
                    .filter(item -> item != null && item.id() != null && !usedIds.contains(item.id()))
                    .findFirst()
                    .orElse(null);
            if (picked != null) {
                usedIds.add(picked.id());
                planned.add(new PlannedActivity(activityTime, picked, querySlots));
            } else {
                planned.add(new PlannedActivity(activityTime, null, querySlots));
            }
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
     * 单时段规划结果：时段 + 命中活动（可能为空）+ 该时段检索用槽位。
     */
    public record PlannedActivity(String period, ActivityItem activity, SlotBundle querySlots) {
        public boolean matched() {
            return activity != null && activity.id() != null;
        }
    }
}

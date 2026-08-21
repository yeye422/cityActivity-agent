package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivitySearchRequest;
import com.city.model.SlotBundle;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多时段规划服务：解析目标活动时段，并按时段拆分检索/重排后各取一款。
 */
@Service
public class ActivityPlanService {

    /** 默认周末规划目标。 */
    public static final List<String> DEFAULT_PLAN_ACTIVITY_TIMES = List.of("周六上午", "周六下午", "周六晚上");

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
        List<String> raw = slots == null || slots.activityTime() == null ? List.of() : slots.activityTime();
        if (raw.isEmpty() || raw.stream().anyMatch(AGGREGATE_WEEKEND::equals)) {
            return DEFAULT_PLAN_ACTIVITY_TIMES;
        }
        List<String> specific = raw.stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(value -> !AGGREGATE_WEEKEND.equals(value))
                .distinct()
                .toList();
        if (specific.size() >= 2) {
            return specific;
        }
        return DEFAULT_PLAN_ACTIVITY_TIMES;
    }

    /**
     * 复制共享槽位，仅替换 activityTime 为单一时段。
     */
    public SlotBundle slotsForActivityTime(SlotBundle base, String activityTime) {
        SlotBundle safe = base == null ? SlotBundle.empty() : base;
        return new SlotBundle(
                safe.city(),
                safe.location(),
                List.of(activityTime),
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
        List<String> targets = activityTimes == null || activityTimes.isEmpty() ? DEFAULT_PLAN_ACTIVITY_TIMES : activityTimes;
        List<PlannedActivity> planned = new ArrayList<>();
        Set<Long> usedIds = new LinkedHashSet<>();

        for (String activityTime : targets) {
            SlotBundle querySlots = slotsForActivityTime(baseSlots, activityTime);
            List<Long> excludeIds = List.copyOf(usedIds);
            List<ActivityItem> candidates = activitySearchService.search(
                    new ActivitySearchRequest(sourceMode, userId, querySlots, excludeIds));
            List<ActivityItem> ranked = activityRankService.rank(
                    new ActivityRankRequest(candidates, querySlots, excludeIds));
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

    /**
     * 单时段规划结果：时段 + 命中活动（可能为空）+ 该时段检索用槽位。
     */
    public record PlannedActivity(String activityTime, ActivityItem activity, SlotBundle querySlots) {
        public boolean matched() {
            return activity != null && activity.id() != null;
        }
    }
}

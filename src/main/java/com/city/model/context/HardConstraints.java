package com.city.model.context;

import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;

import java.util.Set;

/**
 * 服务器可信上下文中九维正向槽位以外的硬约束。
 *
 * <p>完整九维正向过滤条件统一保存在 VerifiedRequestContext.effectiveSlots，
 * 避免在这里重复保存部分槽位导致状态不一致。</p>
 */
public record HardConstraints(
        TimeConstraint timeConstraint,
        SlotBundle excludedSlots,
        Set<Long> excludedActivityIds
) {
    public HardConstraints {
        timeConstraint = timeConstraint == null ? TimeConstraint.empty() : timeConstraint;
        excludedSlots = excludedSlots == null ? SlotBundle.empty() : copySlots(excludedSlots);
        excludedActivityIds = excludedActivityIds == null || excludedActivityIds.isEmpty()
                ? Set.of()
                : Set.copyOf(excludedActivityIds);
    }

    public static HardConstraints empty() {
        return new HardConstraints(TimeConstraint.empty(), SlotBundle.empty(), Set.of());
    }

    private static SlotBundle copySlots(SlotBundle slots) {
        return new SlotBundle(
                slots.city(),
                slots.location(),
                slots.experienceGoal(),
                slots.companion(),
                slots.budget(),
                slots.activityType(),
                slots.style(),
                slots.duration(),
                slots.feature()
        );
    }
}

package com.city.model.context;

import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;

import java.util.List;
import java.util.Set;

/**
 * 当前会话中必须由 Java / 检索层确定性执行的硬约束。
 *
 * <p>第一阶段只承接现有系统已经作为确定性筛选条件使用的维度，
 * 不在这里引入新的自然语言规则，避免为了“语义分层”重新制造一套 Java 意图解析。</p>
 */
public record HardConstraints(
        List<String> cities,
        List<String> locations,
        List<String> budgets,
        List<String> activityTypes,
        List<String> durations,
        List<String> features,
        TimeConstraint timeConstraint,
        SlotBundle excludedSlots,
        Set<Long> excludedActivityIds
) {
    public HardConstraints {
        cities = safeList(cities);
        locations = safeList(locations);
        budgets = safeList(budgets);
        activityTypes = safeList(activityTypes);
        durations = safeList(durations);
        features = safeList(features);
        timeConstraint = timeConstraint == null ? TimeConstraint.empty() : timeConstraint;
        excludedSlots = excludedSlots == null ? SlotBundle.empty() : copySlots(excludedSlots);
        excludedActivityIds = excludedActivityIds == null || excludedActivityIds.isEmpty()
                ? Set.of()
                : Set.copyOf(excludedActivityIds);
    }

    public static HardConstraints empty() {
        return new HardConstraints(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                TimeConstraint.empty(), SlotBundle.empty(), Set.of()
        );
    }

    private static List<String> safeList(List<String> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
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

package com.city.model.context;

import java.util.List;

/**
 * 用于 RecommendationAgent / PlanningAgent 做软目标权衡的用户目标。
 *
 * <p>这里保留语义型槽位，不把它们提前固化成 Java 分数或规则。
 * 后续 Agent 可以基于这些目标判断候选池覆盖度、决定是否二次检索以及如何取舍候选。</p>
 */
public record UserGoal(
        List<String> experienceGoals,
        List<String> companions,
        List<String> styles
) {
    public UserGoal {
        experienceGoals = safeList(experienceGoals);
        companions = safeList(companions);
        styles = safeList(styles);
    }

    public static UserGoal empty() {
        return new UserGoal(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return experienceGoals.isEmpty() && companions.isEmpty() && styles.isEmpty();
    }

    private static List<String> safeList(List<String> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }
}

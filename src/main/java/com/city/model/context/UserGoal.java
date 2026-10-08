package com.city.model.context;

import com.city.model.SlotBundle;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立于九维标准槽位的开放式用户目标。
 * 九维标签用于 MySQL 标准过滤；goals 保存无法准确映射到字典的自由语义目标，
 * 供 Pinecone 和 ReAct Agent 使用，不作为 SQL 等值过滤条件。
 */
public record UserGoal(
        List<String> experienceGoals,
        List<String> companions,
        List<String> styles,
        List<String> goals
) {
    public UserGoal {
        experienceGoals = safeList(experienceGoals);
        companions = safeList(companions);
        styles = safeList(styles);
        goals = safeList(goals);
    }

    public UserGoal(List<String> experienceGoals, List<String> companions, List<String> styles) {
        this(experienceGoals, companions, styles, List.of());
    }

    public static UserGoal empty() {
        return new UserGoal(List.of(), List.of(), List.of(), List.of());
    }

    public static UserGoal open(List<String> goals) {
        return new UserGoal(List.of(), List.of(), List.of(), goals);
    }

    public boolean isEmpty() {
        return experienceGoals.isEmpty() && companions.isEmpty() && styles.isEmpty() && goals.isEmpty();
    }

    /** 纯开放语义内容，可交给检索 Agent 作为软检索目标。 */
    public String semanticQuery() {
        return String.join("，", goals);
    }

    /**
     * 首次检索必须有可用语义查询。
     * 开放目标与已标准化的活动标签一起表达用户意图，但不会修改 MySQL 过滤条件。
     */
    public String semanticQuery(SlotBundle slots) {
        List<String> fragments = new ArrayList<>(goals);
        if (slots != null) {
            fragments.addAll(slots.experienceGoal());
            fragments.addAll(slots.companion());
            fragments.addAll(slots.style());
            fragments.addAll(slots.activityType());
            fragments.addAll(slots.feature());
            fragments.addAll(slots.location());
        }
        return String.join("，", fragments.stream().distinct().toList());
    }

    private static List<String> safeList(List<String> values) {
        return values == null || values.isEmpty() ? List.of()
                : values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
    }
}

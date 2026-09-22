package com.city.service.evaluation;

import com.city.model.ActivityItem;
import com.city.model.SlotBundle;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 检索离线评测使用的稳定活动键。
 *
 * <p>禁止使用数据库自增 activityId 作为人工 relevance 主键，因为不同环境重建 seed 后 ID 会漂移。
 * 当前 key 由 sourceMode + city + activityName 组成；这些字段均来自业务事实，不参与线上实体身份判断。</p>
 */
public final class RetrievalEvaluationKey {
    private RetrievalEvaluationKey() { }

    public static String from(ActivityItem activity) {
        Objects.requireNonNull(activity, "activity");
        String source = activity.sourceType() == null
                ? "UNKNOWN"
                : activity.sourceType().name().toUpperCase(Locale.ROOT);
        String city = cityPart(activity.slots());
        return source + "|" + city + "|" + component(activity.name());
    }

    static String cityPart(SlotBundle slots) {
        if (slots == null || slots.city() == null || slots.city().isEmpty()) return "_";
        return slots.city().stream()
                .filter(Objects::nonNull)
                .map(RetrievalEvaluationKey::component)
                .filter(value -> !value.isBlank())
                .sorted()
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("_");
    }

    private static String component(String value) {
        if (value == null) return "_";
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) return "_";
        return normalized.replace("|", "%7C");
    }
}

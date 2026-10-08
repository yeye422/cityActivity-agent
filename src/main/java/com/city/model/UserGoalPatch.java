package com.city.model;

import com.city.enums.ConstraintOperationType;

import java.util.ArrayList;
import java.util.List;

/** 本轮未命中九维字典的开放语义目标变更；与标准槽位 Patch 独立。 */
public record UserGoalPatch(ConstraintOperationType op, List<String> values) {
    private static final int MAX_GOALS = 8;

    public UserGoalPatch {
        if (op == null) throw new IllegalArgumentException("userGoalPatch.op 不能为空");
        values = values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().limit(MAX_GOALS).toList();
        if (op != ConstraintOperationType.CLEAR && values.isEmpty()) {
            throw new IllegalArgumentException("非 CLEAR UserGoal Patch 必须包含语义目标");
        }
    }

    public List<String> apply(List<String> historical) {
        List<String> result = new ArrayList<>(historical == null ? List.of() : historical);
        switch (op) {
            case CLEAR -> result.clear();
            case SET -> { result.clear(); result.addAll(values); }
            case ADD -> { for (String value : values) if (!result.contains(value)) result.add(value); }
            case REMOVE -> result.removeAll(values);
        }
        return List.copyOf(result.stream().limit(MAX_GOALS).toList());
    }
}

package com.city.model;

import java.util.List;

/** 多样性重排的单条选择决策。reasons 只解释顺序，不参与相关性分数。 */
public record ActivityDiversityDecision(
        Long activityId,
        int position,
        double relevanceScore,
        List<String> reasons
) {
    public ActivityDiversityDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}

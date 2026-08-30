package com.city.model;

import java.util.List;

/** 近似同分候选的多样性重排结果。 */
public record ActivityDiversityResult(
        List<ActivityItem> ranked,
        List<ActivityDiversityDecision> decisions
) {
    public ActivityDiversityResult {
        ranked = ranked == null ? List.of() : List.copyOf(ranked);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
}

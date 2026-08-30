package com.city.model;

import java.util.List;

/** 相关性重排结果：ranked 保留最终相关性顺序，scores 解释每个候选的得分来源。 */
public record ActivityRankResult(
        List<ActivityItem> ranked,
        List<ActivityRankScore> scores
) {
    public ActivityRankResult {
        ranked = ranked == null ? List.of() : List.copyOf(ranked);
        scores = scores == null ? List.of() : List.copyOf(scores);
    }
}

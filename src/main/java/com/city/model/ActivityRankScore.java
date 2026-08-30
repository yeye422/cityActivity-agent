package com.city.model;

/** 单个活动的可解释相关性评分拆解，供 Trace 和回归评估使用。 */
public record ActivityRankScore(
        Long activityId,
        double slotScore,
        Double timeScore,
        double weatherAdjustment,
        double finalScore,
        WeatherRecommendationContext.Status weatherStatus
) {
}

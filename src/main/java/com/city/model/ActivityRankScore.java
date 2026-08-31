package com.city.model;

/** 单个活动的可解释排序评分拆解，供 Trace 和回归评估使用。 */
public record ActivityRankScore(
        Long activityId,
        Double timeScore,
        double weatherAdjustment,
        double finalScore,
        WeatherRecommendationContext.Status weatherStatus
) {
}

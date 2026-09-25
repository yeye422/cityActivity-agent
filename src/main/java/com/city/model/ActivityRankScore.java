package com.city.model;

/** 单个活动的可解释排序评分拆解，供 Trace 和回归评估使用。 */
public record ActivityRankScore(
        Long activityId,
        Double timeScore,
        double weatherAdjustment,
        double lexicalAdjustment,
        double hybridRetrievalAdjustment,
        double preferenceAdjustment,
        double finalScore,
        WeatherRecommendationContext.Status weatherStatus
) {
    /** 兼容引入 Hybrid Retrieval 前的评分构造方式。 */
    public ActivityRankScore(Long activityId,
                             Double timeScore,
                             double weatherAdjustment,
                             double lexicalAdjustment,
                             double preferenceAdjustment,
                             double finalScore,
                             WeatherRecommendationContext.Status weatherStatus) {
        this(activityId, timeScore, weatherAdjustment, lexicalAdjustment, 0.0,
                preferenceAdjustment, finalScore, weatherStatus);
    }
}

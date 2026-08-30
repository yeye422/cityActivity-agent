package com.city.model;

/** 仅在用户明确询问今天、明天或后天时启用的天气排序上下文。 */
public record WeatherRecommendationContext(boolean active, String summary) {
    public static WeatherRecommendationContext inactive() {
        return new WeatherRecommendationContext(false, "");
    }
}

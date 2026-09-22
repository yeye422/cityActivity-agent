package com.city.model;

/** 天气重排上下文；status 用于 Trace 解释天气为何启用或未启用。 */
public record WeatherRecommendationContext(boolean active, Status status, String summary) {

    public enum Status {
        NOT_REQUESTED,
        NO_CITY,
        NO_EXACT_DATE,
        DATE_RANGE_UNSUPPORTED,
        WEATHER_UNAVAILABLE,
        FORECAST_OUT_OF_RANGE,
        WEATHER_NORMAL,
        INDOOR_PRIORITY
    }

    public static WeatherRecommendationContext inactive() {
        return inactive(Status.NOT_REQUESTED);
    }

    public static WeatherRecommendationContext inactive(Status status) {
        return new WeatherRecommendationContext(false, status, "");
    }

    public static WeatherRecommendationContext indoorPriority(String summary) {
        return new WeatherRecommendationContext(true, Status.INDOOR_PRIORITY, summary);
    }
}

package com.city.model;

/** 城市天气卡片所需的精简日预报。 */
public record WeatherDailyForecast(
        String date,
        String textDay,
        String tempMin,
        String tempMax
) {
}

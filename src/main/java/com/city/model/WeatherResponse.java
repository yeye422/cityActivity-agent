package com.city.model;

import java.util.List;

/** 页面展示用天气结果；服务未配置或暂不可用时不会影响活动推荐。 */
public record WeatherResponse(
        boolean available,
        String city,
        String updateTime,
        String text,
        String temperature,
        List<WeatherDailyForecast> daily,
        String message
) {
    public static WeatherResponse unavailable(String city, String message) {
        return new WeatherResponse(false, city, null, null, null, List.of(), message);
    }
}

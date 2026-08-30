package com.city.service.weather;

import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherDailyForecast;
import com.city.model.WeatherRecommendationContext;
import com.city.model.WeatherResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** 将规范化时间状态对应的短期天气转为可解释排序上下文，不把天气作为硬过滤条件。 */
@Service
public class WeatherRecommendationService {
    private final QWeatherService qWeatherService;

    public WeatherRecommendationService(QWeatherService qWeatherService) {
        this.qWeatherService = qWeatherService;
    }

    /**
     * 天气只消费已经解析完成的 TimeConstraint，不再从本轮 userInput 重复识别“今天/明天/后天”。
     * 当前天气源只有三日预报；为避免把区间内某一天的天气错误施加到整个区间，暂只对单日日期启用天气重排。
     */
    public WeatherRecommendationContext resolve(SlotBundle slots, TimeConstraint timeConstraint) {
        if (slots == null || slots.city() == null || slots.city().size() != 1) {
            return WeatherRecommendationContext.inactive(WeatherRecommendationContext.Status.NO_CITY);
        }
        if (timeConstraint == null || !timeConstraint.hasDate()) {
            return WeatherRecommendationContext.inactive(WeatherRecommendationContext.Status.NO_EXACT_DATE);
        }
        if (!timeConstraint.dateStart().equals(timeConstraint.dateEnd())) {
            return WeatherRecommendationContext.inactive(WeatherRecommendationContext.Status.DATE_RANGE_UNSUPPORTED);
        }

        LocalDate targetDate = timeConstraint.dateStart();
        WeatherResponse weather = qWeatherService.weatherFor(slots.city().getFirst());
        if (weather == null || !weather.available()) {
            return WeatherRecommendationContext.inactive(WeatherRecommendationContext.Status.WEATHER_UNAVAILABLE);
        }

        List<WeatherDailyForecast> daily = weather.daily() == null ? List.of() : weather.daily();
        WeatherDailyForecast forecast = daily.stream()
                .filter(item -> item != null && targetDate.toString().equals(item.date()))
                .findFirst()
                .orElse(null);
        if (forecast == null) {
            return WeatherRecommendationContext.inactive(WeatherRecommendationContext.Status.FORECAST_OUT_OF_RANGE);
        }
        if (!requiresIndoorPriority(forecast)) {
            return WeatherRecommendationContext.inactive(WeatherRecommendationContext.Status.WEATHER_NORMAL);
        }

        return WeatherRecommendationContext.indoorPriority(
                targetDate + "预计" + forecast.textDay() + "，"
                        + forecast.tempMin() + "–" + forecast.tempMax() + "℃，已优先室内活动。"
        );
    }

    private boolean requiresIndoorPriority(WeatherDailyForecast forecast) {
        String condition = forecast.textDay() == null ? "" : forecast.textDay();
        if (condition.contains("雨") || condition.contains("雪") || condition.contains("雷")
                || condition.contains("沙") || condition.contains("雾")) {
            return true;
        }
        try {
            return Integer.parseInt(forecast.tempMax()) >= 32 || Integer.parseInt(forecast.tempMin()) <= 5;
        } catch (NumberFormatException | NullPointerException ignored) {
            return false;
        }
    }
}

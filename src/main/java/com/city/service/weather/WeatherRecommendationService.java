package com.city.service.weather;

import com.city.model.SlotBundle;
import com.city.model.WeatherDailyForecast;
import com.city.model.WeatherRecommendationContext;
import com.city.model.WeatherResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/** 将短期天气转为可解释的排序上下文，不把天气作为硬过滤条件。 */
@Service
public class WeatherRecommendationService {
    private final QWeatherService qWeatherService;

    public WeatherRecommendationService(QWeatherService qWeatherService) {
        this.qWeatherService = qWeatherService;
    }

    public WeatherRecommendationContext resolve(String userInput, SlotBundle slots) {
        if (slots == null || slots.city().size() != 1) return WeatherRecommendationContext.inactive();
        int dayOffset = dayOffset(userInput);
        if (dayOffset < 0) return WeatherRecommendationContext.inactive();

        WeatherResponse weather = qWeatherService.weatherFor(slots.city().getFirst());
        List<WeatherDailyForecast> daily = weather.daily();
        if (!weather.available() || daily.size() <= dayOffset) return WeatherRecommendationContext.inactive();
        WeatherDailyForecast forecast = daily.get(dayOffset);
        if (!requiresIndoorPriority(forecast)) return WeatherRecommendationContext.inactive();
        return new WeatherRecommendationContext(true,
                dayLabel(dayOffset) + forecast.date() + "预计" + forecast.textDay() + "，"
                        + forecast.tempMin() + "–" + forecast.tempMax() + "℃，已优先室内活动。");
    }

    private int dayOffset(String userInput) {
        String input = userInput == null ? "" : userInput;
        if (input.contains("后天")) return 2;
        if (input.contains("明天")) return 1;
        if (input.contains("今天")) return 0;
        return -1;
    }

    private boolean requiresIndoorPriority(WeatherDailyForecast forecast) {
        String condition = forecast.textDay();
        if (condition.contains("雨") || condition.contains("雪") || condition.contains("雷")
                || condition.contains("沙") || condition.contains("雾")) return true;
        try {
            return Integer.parseInt(forecast.tempMax()) >= 32 || Integer.parseInt(forecast.tempMin()) <= 5;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String dayLabel(int offset) {
        return switch (offset) {
            case 0 -> "今天";
            case 1 -> "明天";
            default -> "后天";
        };
    }
}

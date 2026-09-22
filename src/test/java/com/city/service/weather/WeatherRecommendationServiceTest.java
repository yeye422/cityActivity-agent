package com.city.service.weather;

import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherDailyForecast;
import com.city.model.WeatherRecommendationContext;
import com.city.model.WeatherResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeatherRecommendationServiceTest {

    @Test
    void shouldUseCanonicalDateEvenWhenCurrentTurnNoLongerContainsDateText() {
        QWeatherService qWeatherService = mock(QWeatherService.class);
        when(qWeatherService.weatherFor("西安")).thenReturn(weather(
                new WeatherDailyForecast("2026-08-31", "小雨", "20", "26")
        ));
        WeatherRecommendationService service = new WeatherRecommendationService(qWeatherService);

        WeatherRecommendationContext result = service.resolve(
                citySlots("西安"),
                exactDate(LocalDate.of(2026, 8, 31), "明天")
        );

        assertTrue(result.active());
        assertEquals(WeatherRecommendationContext.Status.INDOOR_PRIORITY, result.status());
        assertTrue(result.summary().contains("2026-08-31预计小雨"));
        verify(qWeatherService).weatherFor("西安");
    }

    @Test
    void shouldStayInactiveWhenOnlyTimeOfDayIsKnown() {
        QWeatherService qWeatherService = mock(QWeatherService.class);
        WeatherRecommendationService service = new WeatherRecommendationService(qWeatherService);
        TimeConstraint timeOnly = new TimeConstraint(
                "下午到晚上", null, null,
                LocalTime.of(14, 0), LocalTime.of(23, 0), LocalDateTime.now());

        WeatherRecommendationContext result = service.resolve(citySlots("西安"), timeOnly);

        assertFalse(result.active());
        assertEquals(WeatherRecommendationContext.Status.NO_EXACT_DATE, result.status());
        verifyNoInteractions(qWeatherService);
    }

    @Test
    void shouldExposeNormalWeatherWithoutChangingRank() {
        QWeatherService qWeatherService = mock(QWeatherService.class);
        when(qWeatherService.weatherFor("西安")).thenReturn(weather(
                new WeatherDailyForecast("2026-08-31", "晴", "18", "28")
        ));
        WeatherRecommendationService service = new WeatherRecommendationService(qWeatherService);

        WeatherRecommendationContext result = service.resolve(
                citySlots("西安"),
                exactDate(LocalDate.of(2026, 8, 31), "明天")
        );

        assertFalse(result.active());
        assertEquals(WeatherRecommendationContext.Status.WEATHER_NORMAL, result.status());
    }

    @Test
    void shouldNotApplyOneDaysWeatherToWholeDateRange() {
        QWeatherService qWeatherService = mock(QWeatherService.class);
        WeatherRecommendationService service = new WeatherRecommendationService(qWeatherService);
        TimeConstraint range = new TimeConstraint(
                "周末",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 6),
                null, null, LocalDateTime.now());

        WeatherRecommendationContext result = service.resolve(citySlots("西安"), range);

        assertFalse(result.active());
        assertEquals(WeatherRecommendationContext.Status.DATE_RANGE_UNSUPPORTED, result.status());
        verifyNoInteractions(qWeatherService);
    }

    private WeatherResponse weather(WeatherDailyForecast... forecasts) {
        return new WeatherResponse(true, "西安", "", "", "",
                List.of(forecasts), "");
    }

    private SlotBundle citySlots(String city) {
        return new SlotBundle(
                List.of(city), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    private TimeConstraint exactDate(LocalDate date, String raw) {
        return new TimeConstraint(raw, date, date, null, null, LocalDateTime.now());
    }
}

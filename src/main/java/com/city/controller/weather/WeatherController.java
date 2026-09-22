package com.city.controller.weather;

import com.city.model.WeatherResponse;
import com.city.service.weather.QWeatherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 天气仅作为出行参考，失败时不阻断推荐流程。 */
@RestController
@RequestMapping("/api/v1/city/weather")
public class WeatherController {
    private final QWeatherService qWeatherService;

    public WeatherController(QWeatherService qWeatherService) {
        this.qWeatherService = qWeatherService;
    }

    @GetMapping
    public WeatherResponse weather(@RequestParam String city) {
        return qWeatherService.weatherFor(city);
    }
}

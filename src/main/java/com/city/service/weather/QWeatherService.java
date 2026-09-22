package com.city.service.weather;

import com.city.model.WeatherDailyForecast;
import com.city.model.WeatherResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/** 通过和风天气专属 API Host 获取城市实况和未来三天天气。 */
@Service
public class QWeatherService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(20);
    private static final Logger log = LoggerFactory.getLogger(QWeatherService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiHost;
    private final String apiKey;
    private final ConcurrentHashMap<String, CachedWeather> cache = new ConcurrentHashMap<>();

    public QWeatherService(ObjectMapper objectMapper,
                           @Value("${qweather.api-host:}") String apiHost,
                           @Value("${qweather.api-key:}") String apiKey) {
        this.restClient = RestClient.create();
        this.objectMapper = objectMapper;
        this.apiHost = normalizeHost(apiHost);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public WeatherResponse weatherFor(String city) {
        String normalizedCity = normalizeCity(city);
        if (normalizedCity.isBlank()) {
            return WeatherResponse.unavailable("", "请先选择城市");
        }
        if (apiHost.isBlank() || apiKey.isBlank()) {
            return WeatherResponse.unavailable(normalizedCity, "天气服务尚未配置");
        }
        CachedWeather cached = cache.get(normalizedCity);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }
        String stage = "查询城市 LocationID";
        try {
            String locationId = lookupLocationId(normalizedCity);
            stage = "查询实时天气";
            JsonNode now = request("/v7/weather/now", locationId).path("now");
            stage = "查询三日预报";
            JsonNode forecast = request("/v7/weather/3d", locationId);
            List<WeatherDailyForecast> daily = new ArrayList<>();
            for (JsonNode item : forecast.path("daily")) {
                daily.add(new WeatherDailyForecast(
                        item.path("fxDate").asText(), item.path("textDay").asText(),
                        item.path("tempMin").asText(), item.path("tempMax").asText()));
            }
            WeatherResponse result = new WeatherResponse(true, normalizedCity,
                    forecast.path("updateTime").asText(), now.path("text").asText(),
                    now.path("temp").asText(), List.copyOf(daily), "");
            cache.put(normalizedCity, new CachedWeather(result, Instant.now().plus(CACHE_TTL)));
            return result;
        } catch (RestClientResponseException error) {
            String detail = qWeatherResponseDetail(error);
            log.warn("和风天气请求失败：city={}, stage={}, {}", normalizedCity, stage, detail);
            return WeatherResponse.unavailable(normalizedCity, stage + "失败（" + detail + "）");
        } catch (Exception error) {
            String detail = safeErrorMessage(error);
            log.warn("和风天气请求失败：city={}, stage={}, type={}, detail={}",
                    normalizedCity, stage, error.getClass().getSimpleName(), detail);
            return WeatherResponse.unavailable(normalizedCity, stage + "失败（" + detail + "）");
        }
    }

    private String lookupLocationId(String city) throws Exception {
        JsonNode root = request("/geo/v2/city/lookup", city);
        JsonNode locations = root.path("location");
        if (!locations.isArray() || locations.isEmpty()) {
            throw new IllegalStateException("city not found");
        }
        String id = locations.get(0).path("id").asText();
        if (id.isBlank()) throw new IllegalStateException("location id missing");
        return id;
    }

    private JsonNode request(String path, String location) throws Exception {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString("https://" + apiHost + path)
                .queryParam("location", location)
                .queryParam("lang", "zh");
        if (path.startsWith("/geo/")) {
            uri.queryParam("range", "cn").queryParam("number", 1);
        }
        ResponseEntity<byte[]> response = restClient.get()
                .uri(uri.build().encode().toUri())
                .header("X-QW-Api-Key", apiKey)
                .retrieve().toEntity(byte[].class);
        byte[] body = response.getBody() == null ? new byte[0] : response.getBody();
        String contentEncoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (isGzip(contentEncoding, body)) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = gzip.readAllBytes();
            }
        }
        JsonNode root = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
        String code = root.path("code").asText();
        if (!"200".equals(code)) {
            throw new IllegalStateException("和风业务码 " + (code.isBlank() ? "为空" : code));
        }
        return root;
    }

    private boolean isGzip(String contentEncoding, byte[] body) {
        return (contentEncoding != null && contentEncoding.toLowerCase().contains("gzip"))
                || (body.length >= 2 && body[0] == (byte) 0x1f && body[1] == (byte) 0x8b);
    }

    private String safeErrorMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "未提供错误信息";
        }
        String normalized = message.replaceAll("[\\r\\n]+", " ")
                .replaceAll("https?://[^\\s/]+", "[API Host]")
                .trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "…" : normalized;
    }

    /** 只提取和风的错误分类，不回显完整响应体、请求地址或任何凭据。 */
    private String qWeatherResponseDetail(RestClientResponseException error) {
        String fallback = "HTTP " + error.getStatusCode().value();
        try {
            JsonNode root = objectMapper.readTree(error.getResponseBodyAsString());
            JsonNode problem = root.path("error");
            String title = problem.path("title").asText();
            if (!title.isBlank()) {
                return fallback + " · " + title;
            }
            String code = root.path("code").asText();
            return code.isBlank() ? fallback : fallback + " · 和风业务码 " + code;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String normalizeHost(String host) {
        if (host == null) return "";
        return host.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private String normalizeCity(String city) {
        if (city == null) return "";
        String value = city.trim();
        return value.endsWith("市") ? value.substring(0, value.length() - 1) : value;
    }

    private record CachedWeather(WeatherResponse value, Instant expiresAt) {
    }
}

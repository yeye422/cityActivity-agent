package com.city.service.location;

import com.city.exception.CityException;
import com.city.model.ActivitySessionResponse;
import com.city.model.TravelTimeEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;

/**
 * 基于真实场地坐标生成跨场地旅行时间证据。
 *
 * <p>当前使用高德驾车路径规划的最短返回路径作为确定性估时来源。Solver 只消费这里生成的
 * TravelTimeEvidence，不根据地址文本、行政区或直线距离自行猜测旅行时间。</p>
 */
@Service
public class AmapTravelTimeService {
    private static final String DRIVING_ROUTE_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String SOURCE = "AMAP_DRIVING";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public AmapTravelTimeService(ObjectMapper objectMapper,
                                 @Value("${amap.web-service-key:}") String apiKey) {
        this(RestClient.create(), objectMapper, apiKey);
    }

    AmapTravelTimeService(RestClient restClient, ObjectMapper objectMapper, String apiKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public TravelTimeEvidence resolve(ActivitySessionResponse from, ActivitySessionResponse to) {
        validateSession(from, "起点");
        validateSession(to, "终点");
        if (from.venueId().equals(to.venueId())) {
            return new TravelTimeEvidence(from.venueId(), to.venueId(), 0, SOURCE);
        }
        if (apiKey.isBlank()) {
            throw new CityException("路线服务尚未配置，无法校验跨场地交通时间");
        }

        try {
            String body = restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(DRIVING_ROUTE_URL)
                            .queryParam("key", apiKey)
                            .queryParam("origin", coordinate(from.longitude(), from.latitude()))
                            .queryParam("destination", coordinate(to.longitude(), to.latitude()))
                            .queryParam("extensions", "base")
                            .build(true)
                            .toUri())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                throw new CityException("路线服务暂时不可用，无法校验跨场地交通时间");
            }
            JsonNode paths = root.path("route").path("paths");
            if (!paths.isArray() || paths.isEmpty()) {
                throw new CityException("路线服务未返回可用路线");
            }
            long seconds = paths.get(0).path("duration").asLong(-1L);
            if (seconds < 0) {
                throw new CityException("路线服务返回的旅行时间无效");
            }
            int minutes = Math.toIntExact((seconds + 59L) / 60L);
            return new TravelTimeEvidence(from.venueId(), to.venueId(), minutes, SOURCE);
        } catch (CityException error) {
            throw error;
        } catch (Exception error) {
            throw new CityException("路线服务暂时不可用，无法校验跨场地交通时间", error);
        }
    }

    private void validateSession(ActivitySessionResponse session, String side) {
        if (session == null || session.venueId() == null) {
            throw new CityException(side + "场次缺少 venueId，无法计算旅行时间");
        }
        if (!session.hasCoordinates()) {
            throw new CityException(side + "场地缺少经纬度，无法计算旅行时间");
        }
        validateCoordinate(session.longitude(), session.latitude(), side);
    }

    private void validateCoordinate(BigDecimal longitude, BigDecimal latitude, String side) {
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new CityException(side + "场地坐标无效");
        }
    }

    private String coordinate(BigDecimal longitude, BigDecimal latitude) {
        return longitude.stripTrailingZeros().toPlainString() + "," + latitude.stripTrailingZeros().toPlainString();
    }
}

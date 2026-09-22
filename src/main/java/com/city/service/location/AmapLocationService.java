package com.city.service.location;

import com.city.exception.CityException;
import com.city.model.LocationResolveResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 通过高德 Web 服务将经纬度解析为当前城市和区县。 */
@Service
public class AmapLocationService {
    private static final String REVERSE_GEOCODE_URL = "https://restapi.amap.com/v3/geocode/regeo";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public AmapLocationService(ObjectMapper objectMapper,
                               @Value("${amap.web-service-key:}") String apiKey) {
        this.restClient = RestClient.create();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    public LocationResolveResponse resolve(Double longitude, Double latitude) {
        validateCoordinates(longitude, latitude);
        if (apiKey == null || apiKey.isBlank()) {
            throw new CityException("定位服务尚未配置，请联系管理员");
        }

        try {
            String body = restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(REVERSE_GEOCODE_URL)
                            .queryParam("key", apiKey)
                            .queryParam("location", "%.6f,%.6f".formatted(longitude, latitude))
                            .queryParam("extensions", "base")
                            .build(true)
                            .toUri())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                throw new CityException("定位服务暂时不可用，请手动选择城市");
            }
            JsonNode component = root.path("regeocode").path("addressComponent");
            String city = normalizeCity(textValue(component.path("city")));
            if (city.isBlank()) {
                city = normalizeCity(textValue(component.path("province")));
            }
            if (city.isBlank()) {
                throw new CityException("未能识别所在城市，请手动选择");
            }
            return new LocationResolveResponse(city, textValue(component.path("district")));
        } catch (CityException error) {
            throw error;
        } catch (Exception error) {
            throw new CityException("定位服务暂时不可用，请手动选择城市", error);
        }
    }

    private void validateCoordinates(Double longitude, Double latitude) {
        if (longitude == null || latitude == null || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new CityException("定位坐标无效");
        }
    }

    private String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : "";
    }

    private String normalizeCity(String city) {
        return city.endsWith("市") ? city.substring(0, city.length() - 1) : city;
    }
}

package com.city.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DashScopeEmbeddingClient {
    private static final int MAX_BATCH_SIZE = 10;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public DashScopeEmbeddingClient(
            ObjectMapper objectMapper,
            @Value("${city.embedding.enabled:true}") boolean enabled,
            @Value("${agentscope.dashscope.api-key:}") String apiKey,
            @Value("${city.embedding.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${city.embedding.model:text-embedding-v4}") String model,
            @Value("${city.embedding.dimensions:256}") int dimensions
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.dimensions = Math.max(64, dimensions);
        String normalizedBaseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.restClient = normalizedBaseUrl.isBlank() ? null : RestClient.create(normalizedBaseUrl);
    }

    public boolean available() {
        return enabled && restClient != null && !apiKey.isBlank() && !model.isBlank();
    }

    public String modelName() {
        return model;
    }

    public int dimensions() {
        return dimensions;
    }

    public List<List<Double>> embed(List<String> inputs) {
        if (!available()) throw new IllegalStateException("Embedding service is not configured");
        List<String> safeInputs = inputs == null ? List.of() : inputs.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .toList();
        if (safeInputs.isEmpty()) return List.of();
        if (safeInputs.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Embedding batch size cannot exceed " + MAX_BATCH_SIZE);
        }

        String response = restClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", model,
                        "input", safeInputs,
                        "dimensions", dimensions,
                        "encoding_format", "float"
                ))
                .retrieve()
                .body(String.class);

        try {
            JsonNode data = objectMapper.readTree(response == null ? "{}" : response).path("data");
            if (!data.isArray() || data.size() != safeInputs.size()) {
                throw new IllegalStateException("Embedding response size mismatch");
            }
            List<List<Double>> vectors = new ArrayList<>();
            for (int i = 0; i < safeInputs.size(); i++) vectors.add(null);
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode node = item.path("embedding");
                if (index < 0 || index >= vectors.size() || !node.isArray()) {
                    throw new IllegalStateException("Embedding response item is invalid");
                }
                List<Double> vector = new ArrayList<>(node.size());
                node.forEach(value -> vector.add(value.asDouble()));
                if (vector.size() != dimensions) {
                    throw new IllegalStateException("Unexpected embedding dimension: " + vector.size());
                }
                vectors.set(index, List.copyOf(vector));
            }
            if (vectors.stream().anyMatch(value -> value == null || value.isEmpty())) {
                throw new IllegalStateException("Embedding response contains empty vector");
            }
            return List.copyOf(vectors);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse embedding response", error);
        }
    }
}

package com.city.service.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pinecone 2026-07 Document API 客户端。
 *
 * <p>同一个 document-schema index 同时包含：
 * FTS body 字段（Pinecone BM25）与 dense vector 字段。Java 不实现 BM25。</p>
 */
@Service
public class PineconeDocumentClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final String namespace;
    private final String bodyField;
    private final String vectorField;
    private final String apiVersion;

    public PineconeDocumentClient(
            ObjectMapper objectMapper,
            @Value("${city.pinecone.enabled:false}") boolean enabled,
            @Value("${city.pinecone.api-key:}") String apiKey,
            @Value("${city.pinecone.index-host:}") String indexHost,
            @Value("${city.pinecone.namespace:activities}") String namespace,
            @Value("${city.pinecone.body-field:body}") String bodyField,
            @Value("${city.pinecone.vector-field:embedding}") String vectorField,
            @Value("${city.pinecone.api-version:2026-07}") String apiVersion
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.namespace = namespace == null || namespace.isBlank() ? "activities" : namespace.trim();
        this.bodyField = bodyField == null || bodyField.isBlank() ? "body" : bodyField.trim();
        this.vectorField = vectorField == null || vectorField.isBlank() ? "embedding" : vectorField.trim();
        this.apiVersion = apiVersion == null || apiVersion.isBlank() ? "2026-07" : apiVersion.trim();
        String host = indexHost == null ? "" : indexHost.trim().replaceAll("/+$", "");
        if (!host.isBlank() && !host.startsWith("http://") && !host.startsWith("https://")) {
            host = "https://" + host;
        }
        this.restClient = host.isBlank() ? null : RestClient.create(host);
    }

    public boolean available() {
        return enabled && restClient != null && !apiKey.isBlank();
    }

    public void upsert(List<Document> documents) {
        if (!available() || documents == null || documents.isEmpty()) return;
        List<Map<String, Object>> payloadDocs = new ArrayList<>();
        for (Document document : documents) {
            if (document == null || document.activityId() == null) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("_id", String.valueOf(document.activityId()));
            value.put(bodyField, document.body());
            value.put(vectorField, document.embedding());
            value.put("activity_id", document.activityId());
            value.put("source_type", document.sourceType());
            if (document.ownerUserId() != null) {
                value.put("owner_user_id", document.ownerUserId());
            }
            payloadDocs.add(value);
        }
        if (payloadDocs.isEmpty()) return;

        restClient.post()
                .uri(builder -> builder.pathSegment("namespaces", namespace, "documents", "upsert").build())
                .header("Api-Key", apiKey)
                .header("X-Pinecone-Api-Version", apiVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("documents", payloadDocs))
                .retrieve()
                .toBodilessEntity();
    }

    public void delete(Long activityId) {
        if (!available() || activityId == null) return;
        restClient.post()
                .uri(builder -> builder.pathSegment("namespaces", namespace, "documents", "delete").build())
                .header("Api-Key", apiKey)
                .header("X-Pinecone-Api-Version", apiVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ids", List.of(String.valueOf(activityId))))
                .retrieve()
                .toBodilessEntity();
    }

    public Map<Long, Double> searchText(String query, List<Long> candidateIds, int topK) {
        if (!available() || query == null || query.isBlank() || candidateIds == null || candidateIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> scoreBy = Map.of(
                "type", "text",
                "field", bodyField,
                "query", query.trim()
        );
        return search(scoreBy, candidateIds, topK);
    }

    public Map<Long, Double> searchDense(List<Double> queryVector, List<Long> candidateIds, int topK) {
        if (!available() || queryVector == null || queryVector.isEmpty()
                || candidateIds == null || candidateIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> scoreBy = Map.of(
                "type", "dense_vector",
                "field", vectorField,
                "values", queryVector
        );
        return search(scoreBy, candidateIds, topK);
    }

    private Map<Long, Double> search(Map<String, Object> scoreBy, List<Long> candidateIds, int topK) {
        Map<String, Object> filter = Map.of(
                "activity_id", Map.of("$in", candidateIds)
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("score_by", List.of(scoreBy));
        body.put("top_k", Math.max(1, topK));
        body.put("filter", filter);
        body.put("include_fields", List.of());

        String response = restClient.post()
                .uri(builder -> builder.pathSegment("namespaces", namespace, "documents", "search").build())
                .header("Api-Key", apiKey)
                .header("X-Pinecone-Api-Version", apiVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode matches = objectMapper.readTree(response == null ? "{}" : response).path("matches");
            if (!matches.isArray()) return Map.of();
            Map<Long, Double> scores = new LinkedHashMap<>();
            for (JsonNode match : matches) {
                String id = match.path("_id").asText(match.path("id").asText(""));
                double score = match.has("_score")
                        ? match.path("_score").asDouble(Double.NaN)
                        : match.path("score").asDouble(Double.NaN);
                try {
                    Long activityId = Long.valueOf(id);
                    if (Double.isFinite(score)) scores.put(activityId, score);
                } catch (NumberFormatException ignored) {
                    // 只接受本项目约定的数字 activityId。
                }
            }
            return Map.copyOf(scores);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse Pinecone document search response", error);
        }
    }

    public record Document(
            Long activityId,
            String body,
            List<Double> embedding,
            String sourceType,
            Long ownerUserId
    ) {
        public Document {
            body = body == null ? "" : body;
            embedding = embedding == null ? List.of() : List.copyOf(embedding);
            sourceType = sourceType == null ? "" : sourceType;
        }
    }
}

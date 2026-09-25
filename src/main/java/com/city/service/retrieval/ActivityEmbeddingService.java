package com.city.service.retrieval;

import com.city.mapper.ActivityEmbeddingMapper;
import com.city.model.ActivityEmbeddingRow;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ActivityEmbeddingService {
    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {};
    private static final int MAX_BATCH_SIZE = 10;

    private final ActivityEmbeddingMapper mapper;
    private final DashScopeEmbeddingClient client;
    private final ObjectMapper objectMapper;

    public ActivityEmbeddingService(ActivityEmbeddingMapper mapper,
                                    DashScopeEmbeddingClient client,
                                    ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.client = Objects.requireNonNull(client, "client");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public boolean available() {
        return client.available();
    }

    public List<Double> embedQuery(String query) {
        if (query == null || query.isBlank() || !available()) return List.of();
        List<List<Double>> vectors = client.embed(List.of(query.trim()));
        return vectors.isEmpty() ? List.of() : vectors.getFirst();
    }

    public Map<Long, List<Double>> vectorsFor(List<ActivityItem> activities) {
        if (!available() || activities == null || activities.isEmpty()) return Map.of();

        List<ActivityItem> items = activities.stream()
                .filter(item -> item != null && item.id() != null)
                .toList();
        if (items.isEmpty()) return Map.of();

        Map<Long, ActivityEmbeddingRow> existing = new LinkedHashMap<>();
        List<ActivityEmbeddingRow> rows = mapper.findByActivityIds(
                items.stream().map(ActivityItem::id).distinct().toList());
        if (rows != null) {
            for (ActivityEmbeddingRow row : rows) {
                if (row != null && row.getActivityId() != null) existing.put(row.getActivityId(), row);
            }
        }

        Map<Long, List<Double>> result = new LinkedHashMap<>();
        List<PendingDocument> pending = new ArrayList<>();
        for (ActivityItem item : items) {
            String document = semanticDocument(item);
            String sourceHash = sha256(document);
            ActivityEmbeddingRow row = existing.get(item.id());
            if (matches(row, sourceHash)) {
                List<Double> vector = parseVector(row.getEmbeddingJson());
                if (vector.size() == client.dimensions()) {
                    result.put(item.id(), vector);
                    continue;
                }
            }
            pending.add(new PendingDocument(item.id(), sourceHash, document));
        }

        for (int start = 0; start < pending.size(); start += MAX_BATCH_SIZE) {
            List<PendingDocument> batch = pending.subList(start, Math.min(start + MAX_BATCH_SIZE, pending.size()));
            List<List<Double>> vectors = client.embed(batch.stream().map(PendingDocument::document).toList());
            if (vectors.size() != batch.size()) throw new IllegalStateException("Embedding batch result size mismatch");
            for (int i = 0; i < batch.size(); i++) {
                PendingDocument document = batch.get(i);
                List<Double> vector = vectors.get(i);
                ActivityEmbeddingRow row = new ActivityEmbeddingRow();
                row.setActivityId(document.activityId());
                row.setSourceHash(document.sourceHash());
                row.setModel(client.modelName());
                row.setDimensions(client.dimensions());
                row.setEmbeddingJson(toJson(vector));
                mapper.upsert(row);
                result.put(document.activityId(), vector);
            }
        }
        return Map.copyOf(result);
    }

    String semanticDocument(ActivityItem item) {
        SlotBundle slots = item.slots() == null ? SlotBundle.empty() : item.slots();
        List<String> sections = new ArrayList<>();
        sections.add("活动：" + safe(item.name()));
        if (item.description() != null && !item.description().isBlank()) {
            sections.add("描述：" + item.description().trim());
        }
        append(sections, "城市", slots.city());
        append(sections, "地点", slots.location());
        append(sections, "体验目标", slots.experienceGoal());
        append(sections, "同行", slots.companion());
        append(sections, "预算", slots.budget());
        append(sections, "类型", slots.activityType());
        append(sections, "风格", slots.style());
        append(sections, "时长", slots.duration());
        append(sections, "特征", slots.feature());
        return String.join("；", sections);
    }

    private boolean matches(ActivityEmbeddingRow row, String sourceHash) {
        return row != null
                && sourceHash.equals(row.getSourceHash())
                && client.modelName().equals(row.getModel())
                && Objects.equals(client.dimensions(), row.getDimensions());
    }

    private List<Double> parseVector(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Double> vector = objectMapper.readValue(json, DOUBLE_LIST);
            return vector == null ? List.of() : List.copyOf(vector);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String toJson(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize embedding", error);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void append(List<String> sections, String label, List<String> values) {
        if (values != null && !values.isEmpty()) sections.add(label + "：" + String.join("、", values));
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record PendingDocument(Long activityId, String sourceHash, String document) {}
}

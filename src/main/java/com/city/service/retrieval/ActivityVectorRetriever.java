package com.city.service.retrieval;

import com.city.model.ActivityItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ActivityVectorRetriever {
    private static final Logger log = LoggerFactory.getLogger(ActivityVectorRetriever.class);
    private final ActivityEmbeddingService embeddingService;

    public ActivityVectorRetriever(ActivityEmbeddingService embeddingService) {
        this.embeddingService = Objects.requireNonNull(embeddingService, "embeddingService");
    }

    public Map<Long, Double> score(List<ActivityItem> candidates, String queryText) {
        if (queryText == null || queryText.isBlank()
                || candidates == null || candidates.isEmpty()
                || !embeddingService.available()) return Map.of();
        try {
            List<Double> query = embeddingService.embedQuery(queryText);
            if (query.isEmpty()) return Map.of();
            Map<Long, List<Double>> documents = embeddingService.vectorsFor(candidates);
            Map<Long, Double> result = new LinkedHashMap<>();
            for (ActivityItem item : candidates) {
                if (item == null || item.id() == null) continue;
                List<Double> vector = documents.get(item.id());
                if (vector == null || vector.isEmpty()) continue;
                double similarity = cosine(query, vector);
                if (Double.isFinite(similarity)) {
                    result.put(item.id(), Math.max(-1.0, Math.min(1.0, similarity)));
                }
            }
            return Map.copyOf(result);
        } catch (RuntimeException error) {
            log.warn("Vector retrieval unavailable; fallback to lexical retrieval: {}", error.getMessage());
            return Map.of();
        }
    }

    static double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) return 0.0;
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}

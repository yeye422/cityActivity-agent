package com.city.service.retrieval;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReciprocalRankFusionService {
    static final int RRF_K = 60;

    public Map<Long, Double> fuse(List<Long> lexicalRank, List<Long> vectorRank) {
        Map<Long, Double> raw = new LinkedHashMap<>();
        addRank(raw, lexicalRank);
        addRank(raw, vectorRank);
        if (raw.isEmpty()) return Map.of();

        double max = raw.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        Map<Long, Double> normalized = new LinkedHashMap<>();
        raw.forEach((id, value) -> normalized.put(id, max <= 0.0 ? 0.0 : value / max));
        return Map.copyOf(normalized);
    }

    private void addRank(Map<Long, Double> scores, List<Long> rankedIds) {
        if (rankedIds == null || rankedIds.isEmpty()) return;
        int rank = 1;
        for (Long id : rankedIds) {
            if (id == null) continue;
            scores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
            rank++;
        }
    }
}

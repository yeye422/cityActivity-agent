package com.city.service.evaluation;

import com.city.model.ActivityItem;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 使用稳定 activityKey 的检索离线评测器。
 *
 * <p>人工 relevance 使用字符串键，不依赖数据库 ID；运行时 ranked ActivityItem 会先经
 * {@link RetrievalEvaluationKey} 转换，再计算 Recall@K/NDCG@K。</p>
 */
public final class StableRetrievalQualityEvaluator {
    private StableRetrievalQualityEvaluator() { }

    public static CaseResult evaluate(CaseDefinition testCase,
                                      List<ActivityItem> rankedActivities,
                                      int k) {
        Objects.requireNonNull(testCase, "testCase");
        if (k <= 0) throw new IllegalArgumentException("k 必须大于 0");

        List<String> rankedKeys = rankedActivities == null
                ? List.of()
                : rankedActivities.stream()
                .filter(Objects::nonNull)
                .map(RetrievalEvaluationKey::from)
                .distinct()
                .toList();
        List<String> topK = rankedKeys.stream().limit(k).toList();
        Map<String, Double> relevance = testCase.relevanceByActivityKey();
        Set<String> relevantKeys = relevance.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0.0)
                .map(Map.Entry::getKey)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        Double recallAtK = relevantKeys.isEmpty()
                ? null
                : topK.stream().filter(relevantKeys::contains).count() / (double) relevantKeys.size();
        Double ndcgAtK = ndcg(topK, relevance, k);
        Double noResultFalsePositive = testCase.expectedNoResult()
                ? (rankedKeys.isEmpty() ? 0.0 : 1.0)
                : null;

        return new CaseResult(testCase.caseId(), k, topK, recallAtK, ndcgAtK, noResultFalsePositive);
    }

    private static Double ndcg(List<String> ranked,
                               Map<String, Double> relevance,
                               int k) {
        List<Double> idealGrades = relevance.values().stream()
                .filter(Objects::nonNull)
                .filter(value -> value > 0.0)
                .sorted(Comparator.reverseOrder())
                .limit(k)
                .toList();
        if (idealGrades.isEmpty()) return null;

        double dcg = 0.0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            double grade = Math.max(0.0, relevance.getOrDefault(ranked.get(i), 0.0));
            dcg += gain(grade) / log2(i + 2.0);
        }

        double idcg = 0.0;
        for (int i = 0; i < idealGrades.size(); i++) {
            idcg += gain(idealGrades.get(i)) / log2(i + 2.0);
        }
        return idcg == 0.0 ? null : dcg / idcg;
    }

    private static double gain(double grade) {
        return Math.pow(2.0, grade) - 1.0;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    public record CaseDefinition(
            String caseId,
            Map<String, Double> relevanceByActivityKey,
            boolean expectedNoResult
    ) {
        public CaseDefinition {
            if (caseId == null || caseId.isBlank()) {
                throw new IllegalArgumentException("caseId 不能为空");
            }
            Map<String, Double> normalized = new LinkedHashMap<>();
            if (relevanceByActivityKey != null) {
                relevanceByActivityKey.forEach((key, grade) -> {
                    if (key != null && !key.isBlank() && grade != null && grade >= 0.0) {
                        normalized.put(key.trim(), grade);
                    }
                });
            }
            relevanceByActivityKey = Map.copyOf(normalized);
        }
    }

    public record CaseResult(
            String caseId,
            int k,
            List<String> topKActivityKeys,
            Double recallAtK,
            Double ndcgAtK,
            Double noResultFalsePositive
    ) {
        public CaseResult {
            topKActivityKeys = topKActivityKeys == null ? List.of() : List.copyOf(topKActivityKeys);
        }
    }
}

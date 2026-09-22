package com.city.service.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索质量的纯函数评测器。
 *
 * <p>不依赖数据库、Embedding 或具体 Retrieval 实现，只消费最终 ranked activityId 和人工 relevance。
 * 因此同一套指标可以公平比较 BM25 only / Vector only / BM25 + Vector + RRF。</p>
 */
public final class RetrievalQualityEvaluator {

    private RetrievalQualityEvaluator() { }

    public static CaseResult evaluate(CaseDefinition testCase, List<Long> rankedActivityIds, int k) {
        Objects.requireNonNull(testCase, "testCase");
        if (k <= 0) throw new IllegalArgumentException("k 必须大于 0");

        List<Long> ranked = rankedActivityIds == null
                ? List.of()
                : rankedActivityIds.stream().filter(Objects::nonNull).distinct().toList();
        List<Long> topK = ranked.stream().limit(k).toList();
        Map<Long, Double> relevance = testCase.relevanceByActivityId();
        Set<Long> relevantIds = relevance.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0.0)
                .map(Map.Entry::getKey)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);

        Double recallAtK = relevantIds.isEmpty()
                ? null
                : topK.stream().filter(relevantIds::contains).count() / (double) relevantIds.size();
        Double ndcgAtK = ndcg(topK, relevance, k);
        Double noResultFalsePositive = testCase.expectedNoResult()
                ? (ranked.isEmpty() ? 0.0 : 1.0)
                : null;

        return new CaseResult(
                testCase.caseId(),
                k,
                topK,
                recallAtK,
                ndcgAtK,
                noResultFalsePositive
        );
    }

    public static Summary summarize(List<CaseResult> results) {
        List<CaseResult> safe = results == null ? List.of() : results.stream()
                .filter(Objects::nonNull)
                .toList();
        return new Summary(
                safe.size(),
                average(safe.stream().map(CaseResult::recallAtK).toList()),
                average(safe.stream().map(CaseResult::ndcgAtK).toList()),
                average(safe.stream().map(CaseResult::noResultFalsePositive).toList())
        );
    }

    private static Double ndcg(List<Long> ranked,
                               Map<Long, Double> relevance,
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

    private static Double average(List<Double> values) {
        List<Double> present = values.stream().filter(Objects::nonNull).toList();
        return present.isEmpty()
                ? null
                : present.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    public record CaseDefinition(
            String caseId,
            Map<Long, Double> relevanceByActivityId,
            boolean expectedNoResult
    ) {
        public CaseDefinition {
            if (caseId == null || caseId.isBlank()) {
                throw new IllegalArgumentException("caseId 不能为空");
            }
            Map<Long, Double> normalized = new LinkedHashMap<>();
            if (relevanceByActivityId != null) {
                relevanceByActivityId.forEach((id, grade) -> {
                    if (id != null && grade != null && grade >= 0.0) {
                        normalized.put(id, grade);
                    }
                });
            }
            relevanceByActivityId = Map.copyOf(normalized);
        }
    }

    public record CaseResult(
            String caseId,
            int k,
            List<Long> topKActivityIds,
            Double recallAtK,
            Double ndcgAtK,
            Double noResultFalsePositive
    ) {
        public CaseResult {
            topKActivityIds = topKActivityIds == null ? List.of() : List.copyOf(topKActivityIds);
        }
    }

    public record Summary(
            int totalCases,
            Double recallAtK,
            Double ndcgAtK,
            Double noResultFalsePositiveRate
    ) { }
}

package com.city.service.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RetrievalQualityEvaluatorTest {

    @Test
    void shouldCalculateRecallAndNdcgAtK() {
        RetrievalQualityEvaluator.CaseDefinition testCase =
                new RetrievalQualityEvaluator.CaseDefinition(
                        "date-interactive",
                        Map.of(1L, 3.0, 2L, 2.0, 3L, 1.0),
                        false
                );

        RetrievalQualityEvaluator.CaseResult result =
                RetrievalQualityEvaluator.evaluate(testCase, List.of(2L, 9L, 1L, 8L), 3);

        assertEquals(2.0 / 3.0, result.recallAtK(), 1e-9);
        // 2L 和 1L 都被召回，但最佳 3 分结果没有排在第一，因此 NDCG 应小于 1。
        assertEquals(true, result.ndcgAtK() > 0.0 && result.ndcgAtK() < 1.0);
        assertNull(result.noResultFalsePositive());
    }

    @Test
    void perfectRankingShouldHaveNdcgOne() {
        RetrievalQualityEvaluator.CaseDefinition testCase =
                new RetrievalQualityEvaluator.CaseDefinition(
                        "perfect",
                        Map.of(1L, 3.0, 2L, 2.0, 3L, 1.0),
                        false
                );

        RetrievalQualityEvaluator.CaseResult result =
                RetrievalQualityEvaluator.evaluate(testCase, List.of(1L, 2L, 3L), 3);

        assertEquals(1.0, result.recallAtK(), 1e-9);
        assertEquals(1.0, result.ndcgAtK(), 1e-9);
    }

    @Test
    void noResultCaseShouldMeasureFalsePositiveOnly() {
        RetrievalQualityEvaluator.CaseDefinition testCase =
                new RetrievalQualityEvaluator.CaseDefinition("no-result", Map.of(), true);

        RetrievalQualityEvaluator.CaseResult empty =
                RetrievalQualityEvaluator.evaluate(testCase, List.of(), 5);
        RetrievalQualityEvaluator.CaseResult falsePositive =
                RetrievalQualityEvaluator.evaluate(testCase, List.of(99L), 5);

        assertNull(empty.recallAtK());
        assertNull(empty.ndcgAtK());
        assertEquals(0.0, empty.noResultFalsePositive(), 1e-9);
        assertEquals(1.0, falsePositive.noResultFalsePositive(), 1e-9);
    }

    @Test
    void summaryShouldIgnoreMetricsThatAreNotApplicable() {
        RetrievalQualityEvaluator.Summary summary = RetrievalQualityEvaluator.summarize(List.of(
                new RetrievalQualityEvaluator.CaseResult("a", 5, List.of(1L), 1.0, 0.8, null),
                new RetrievalQualityEvaluator.CaseResult("b", 5, List.of(), null, null, 0.0)
        ));

        assertEquals(2, summary.totalCases());
        assertEquals(1.0, summary.recallAtK(), 1e-9);
        assertEquals(0.8, summary.ndcgAtK(), 1e-9);
        assertEquals(0.0, summary.noResultFalsePositiveRate(), 1e-9);
    }
}

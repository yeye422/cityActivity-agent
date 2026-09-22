package com.city.service.evaluation;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableRetrievalQualityEvaluatorTest {

    @Test
    void activityKeyShouldNotDependOnDatabaseId() {
        ActivityItem first = activity(101L, "西安", "曲江艺术中心周末特展");
        ActivityItem rebuilt = activity(9999L, "西安", "曲江艺术中心周末特展");

        assertEquals("PUBLIC|西安|曲江艺术中心周末特展", RetrievalEvaluationKey.from(first));
        assertEquals(RetrievalEvaluationKey.from(first), RetrievalEvaluationKey.from(rebuilt));
    }

    @Test
    void shouldCalculateMetricsUsingStableKeys() {
        ActivityItem exhibit = activity(10L, "西安", "曲江艺术中心周末特展");
        ActivityItem cinema = activity(20L, "西安", "小寨独立影院观影");
        ActivityItem climbing = activity(30L, "西安", "高新室内攀岩体验课");
        StableRetrievalQualityEvaluator.CaseDefinition testCase =
                new StableRetrievalQualityEvaluator.CaseDefinition(
                        "date-art",
                        Map.of(
                                "PUBLIC|西安|曲江艺术中心周末特展", 3.0,
                                "PUBLIC|西安|小寨独立影院观影", 2.0
                        ),
                        false
                );

        StableRetrievalQualityEvaluator.CaseResult result =
                StableRetrievalQualityEvaluator.evaluate(
                        testCase,
                        List.of(cinema, climbing, exhibit),
                        2
                );

        assertEquals(0.5, result.recallAtK(), 1e-9);
        assertTrue(result.ndcgAtK() > 0.0 && result.ndcgAtK() < 1.0);
        assertNull(result.noResultFalsePositive());
    }

    @Test
    void relevanceResourceShouldUseStableStringKeysOnly() throws Exception {
        try (InputStream input = new ClassPathResource(
                "evaluation/retrieval-relevance-v1.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(input);
            assertEquals("retrieval-v1", root.path("version").asText());
            assertTrue(root.path("cases").isArray());
            assertTrue(root.path("cases").size() >= 6);

            for (JsonNode testCase : root.path("cases")) {
                assertFalse(testCase.path("id").asText().isBlank());
                assertFalse(testCase.path("query").asText().isBlank());
                JsonNode relevance = testCase.path("relevance");
                assertTrue(relevance.isObject());
                relevance.fieldNames().forEachRemaining(key -> {
                    assertTrue(key.split("\\|", -1).length == 3,
                            () -> "非法 retrieval relevance key: " + key);
                    assertFalse(key.matches("\\d+"),
                            () -> "relevance 不应使用数据库自增 ID: " + key);
                });
            }
        }
    }

    @Test
    void noResultCaseShouldOnlyMeasureFalsePositive() {
        StableRetrievalQualityEvaluator.CaseDefinition noResult =
                new StableRetrievalQualityEvaluator.CaseDefinition("none", Map.of(), true);

        StableRetrievalQualityEvaluator.CaseResult empty =
                StableRetrievalQualityEvaluator.evaluate(noResult, List.of(), 5);
        StableRetrievalQualityEvaluator.CaseResult falsePositive =
                StableRetrievalQualityEvaluator.evaluate(
                        noResult,
                        List.of(activity(1L, "西安", "任意活动")),
                        5
                );

        assertNull(empty.recallAtK());
        assertNull(empty.ndcgAtK());
        assertEquals(0.0, empty.noResultFalsePositive(), 1e-9);
        assertEquals(1.0, falsePositive.noResultFalsePositive(), 1e-9);
    }

    private ActivityItem activity(Long id, String city, String name) {
        SlotBundle slots = new SlotBundle(
                List.of(city), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, name, slots,
                null, null, null, null, 120, 0.8);
    }
}

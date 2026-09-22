package com.city.service.evaluation;

import com.city.model.RequestTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentRuntimeMetricsExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentRuntimeMetricsExtractor extractor = new AgentRuntimeMetricsExtractor(objectMapper);

    @Test
    void shouldExtractRecommendationReretrievalAndSuccess() {
        RequestTraceRow trace = trace("""
                {
                  "events": [
                    {"eventType":"RECOMMENDATION_REACT_ROUTE_SELECTED"},
                    {"eventType":"RETRIEVAL_TOOL_CALLED"},
                    {"eventType":"RETRIEVAL_TOOL_COMPLETED"},
                    {"eventType":"RETRIEVAL_TOOL_CALLED"},
                    {"eventType":"RETRIEVAL_TOOL_COMPLETED"},
                    {"eventType":"RECOMMENDATION_REACT_MAINLINE_USED"}
                  ]
                }
                """);

        Map<String, Double> metrics = extractor.aggregate(List.of(trace));

        assertEquals(1.0, metrics.get("reactRouteCoverage"));
        assertEquals(1.0, metrics.get("recommendationReactSuccessRate"));
        assertEquals(1.0, metrics.get("reRetrievalRate"));
        assertEquals(2.0, metrics.get("retrievalToolCallCount"));
        assertEquals(2.0, metrics.get("reactToolCallCount"));
        assertEquals(0.0, metrics.get("reactFallbackRate"));
        assertEquals(0.0, metrics.get("evidenceViolationRate"));
    }

    @Test
    void shouldExtractPlanningRepairSuccess() {
        RequestTraceRow trace = trace("""
                {
                  "events": [
                    {"eventType":"PLANNING_REACT_ROUTE_SELECTED"},
                    {"eventType":"PLANNING_DISCOVERY_TOOL_CALLED"},
                    {"eventType":"TRAVEL_TIME_TOOL_CALLED"},
                    {"eventType":"PLAN_VALIDATION_TOOL_CALLED"},
                    {"eventType":"PLAN_VALIDATION_FAILED"},
                    {"eventType":"PLAN_VALIDATION_TOOL_CALLED"},
                    {"eventType":"PLAN_VALIDATION_PASSED"},
                    {"eventType":"PLANNING_REACT_MAINLINE_USED"}
                  ]
                }
                """);

        Map<String, Double> metrics = extractor.aggregate(List.of(trace));

        assertEquals(1.0, metrics.get("reactRouteCoverage"));
        assertEquals(1.0, metrics.get("planningReactSuccessRate"));
        assertEquals(1.0, metrics.get("planValidationFailureRate"));
        assertEquals(1.0, metrics.get("planRepairSuccessRate"));
        assertEquals(2.0, metrics.get("planValidationCallCount"));
        assertEquals(1.0, metrics.get("travelToolCallCount"));
        assertEquals(4.0, metrics.get("reactToolCallCount"));
    }

    @Test
    void shouldMarkEvidenceViolationAndFallback() {
        RequestTraceRow trace = trace("""
                {
                  "events": [
                    {"eventType":"RECOMMENDATION_REACT_ROUTE_SELECTED"},
                    {"eventType":"RECOMMENDATION_AGENT_FAILED","errorMessage":"IllegalStateException: 推荐结果引用当前 Run 未登记 Activity Evidence: [999]"},
                    {"eventType":"RECOMMENDATION_REACT_FALLBACK"}
                  ]
                }
                """);

        Map<String, Double> metrics = extractor.aggregate(List.of(trace));

        assertEquals(1.0, metrics.get("reactRouteCoverage"));
        assertEquals(0.0, metrics.get("recommendationReactSuccessRate"));
        assertEquals(1.0, metrics.get("reactFallbackRate"));
        assertEquals(1.0, metrics.get("evidenceViolationRate"));
    }

    @Test
    void shouldNotTreatLegacyTraceAsZeroReactSuccess() {
        RequestTraceRow trace = trace("""
                {"events":[{"eventType":"ACTIVITY_RANKED"}]}
                """);

        Map<String, Double> metrics = extractor.aggregate(List.of(trace));

        assertEquals(0.0, metrics.get("reactRouteCoverage"));
        assertFalse(metrics.containsKey("recommendationReactSuccessRate"));
        assertFalse(metrics.containsKey("planningReactSuccessRate"));
        assertFalse(metrics.containsKey("reactFallbackRate"));
    }

    @Test
    void shouldAverageRouteCoverageAcrossAllTraces() {
        RequestTraceRow react = trace("""
                {"events":[{"eventType":"RECOMMENDATION_REACT_ROUTE_SELECTED"}]}
                """);
        RequestTraceRow clarify = trace("""
                {"events":[{"eventType":"CLARIFY_DECISION"}]}
                """);

        Map<String, Double> metrics = extractor.aggregate(List.of(react, clarify));

        assertEquals(0.5, metrics.get("reactRouteCoverage"));
    }

    private RequestTraceRow trace(String traceJson) {
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId("trace_test");
        row.setTraceJson(traceJson);
        return row;
    }
}

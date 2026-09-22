package com.city.service.evaluation;

import com.city.model.RequestTraceRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从现有 request trace 事件中提取 AgentScope ReAct 运行指标。
 *
 * <p>不新增数据库字段，只消费 trace_json.events；没有启用 ReAct 的历史 Trace 对相关比例返回 null，
 * 因而不会被错误计入 0 分。</p>
 */
public final class AgentRuntimeMetricsExtractor {

    private final ObjectMapper objectMapper;

    public AgentRuntimeMetricsExtractor(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Map<String, Double> aggregate(List<RequestTraceRow> traces) {
        List<RequestTraceRow> safe = traces == null ? List.of() : traces;
        Map<String, List<Double>> values = new LinkedHashMap<>();
        for (RequestTraceRow trace : safe) {
            perTrace(trace).forEach((name, value) -> {
                if (value != null) {
                    values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
                }
            });
        }
        Map<String, Double> result = new LinkedHashMap<>();
        values.forEach((name, metricValues) -> result.put(name,
                metricValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)));
        return result;
    }

    Map<String, Double> perTrace(RequestTraceRow trace) {
        RuntimeFacts facts = parse(trace);
        Map<String, Double> metrics = new LinkedHashMap<>();

        metrics.put("recommendationReactSuccessRate", facts.recommendationRouteSelected()
                ? bool(facts.recommendationMainlineUsed()) : null);
        metrics.put("planningReactSuccessRate", facts.planningRouteSelected()
                ? bool(facts.planningMainlineUsed()) : null);
        metrics.put("reactFallbackRate", facts.anyReactRouteSelected()
                ? bool(facts.reactFallback()) : null);
        metrics.put("reactToolCallCount", facts.anyReactRouteSelected()
                ? (double) facts.toolCalls() : null);
        metrics.put("retrievalToolCallCount", facts.recommendationRouteSelected()
                ? (double) facts.retrievalCalls() : null);
        metrics.put("reRetrievalRate", facts.recommendationRouteSelected()
                ? bool(facts.retrievalCalls() > 1) : null);
        metrics.put("travelToolCallCount", facts.planningRouteSelected()
                ? (double) facts.travelCalls() : null);
        metrics.put("planValidationCallCount", facts.planningRouteSelected()
                ? (double) facts.planValidationCalls() : null);
        metrics.put("planValidationFailureRate", facts.planningRouteSelected()
                ? bool(facts.planValidationFailures() > 0) : null);
        metrics.put("planRepairSuccessRate", facts.planValidationFailures() > 0
                ? bool(facts.planValidationPasses() > 0 && facts.planningMainlineUsed()) : null);
        metrics.put("evidenceViolationRate", facts.anyReactRouteSelected()
                ? bool(facts.evidenceViolation()) : null);

        return metrics;
    }

    private RuntimeFacts parse(RequestTraceRow trace) {
        JsonNode root = readTree(trace == null ? null : trace.getTraceJson());
        JsonNode events = root.path("events");

        boolean recommendationRouteSelected = false;
        boolean recommendationMainlineUsed = false;
        boolean planningRouteSelected = false;
        boolean planningMainlineUsed = false;
        boolean reactFallback = false;
        boolean evidenceViolation = false;
        int toolCalls = 0;
        int retrievalCalls = 0;
        int travelCalls = 0;
        int planValidationCalls = 0;
        int planValidationFailures = 0;
        int planValidationPasses = 0;

        if (events.isArray()) {
            for (JsonNode event : events) {
                String type = event.path("eventType").asText("");
                String error = event.path("errorMessage").asText("");
                String input = event.path("inputPayload").asText("");
                String output = event.path("outputPayload").asText("");

                if ("RECOMMENDATION_REACT_ROUTE_SELECTED".equals(type)) recommendationRouteSelected = true;
                if ("RECOMMENDATION_REACT_MAINLINE_USED".equals(type)) recommendationMainlineUsed = true;
                if ("PLANNING_REACT_ROUTE_SELECTED".equals(type)) planningRouteSelected = true;
                if ("PLANNING_REACT_MAINLINE_USED".equals(type)) planningMainlineUsed = true;
                if ("RECOMMENDATION_REACT_FALLBACK".equals(type)
                        || "PLANNING_REACT_FALLBACK".equals(type)) reactFallback = true;

                if (type.endsWith("_TOOL_CALLED")) toolCalls++;
                if ("RETRIEVAL_TOOL_CALLED".equals(type)) retrievalCalls++;
                if ("TRAVEL_TIME_TOOL_CALLED".equals(type)) travelCalls++;
                if ("PLAN_VALIDATION_TOOL_CALLED".equals(type)) planValidationCalls++;
                if ("PLAN_VALIDATION_FAILED".equals(type)) planValidationFailures++;
                if ("PLAN_VALIDATION_PASSED".equals(type)) planValidationPasses++;

                if (containsEvidenceViolation(error)
                        || containsEvidenceViolation(input)
                        || containsEvidenceViolation(output)) {
                    evidenceViolation = true;
                }
            }
        }

        return new RuntimeFacts(
                recommendationRouteSelected,
                recommendationMainlineUsed,
                planningRouteSelected,
                planningMainlineUsed,
                reactFallback,
                evidenceViolation,
                toolCalls,
                retrievalCalls,
                travelCalls,
                planValidationCalls,
                planValidationFailures,
                planValidationPasses
        );
    }

    private boolean containsEvidenceViolation(String value) {
        if (value == null || value.isBlank()) return false;
        return value.contains("未登记 Activity Evidence")
                || value.contains("未登记 Session Evidence")
                || value.contains("缺少当前 Run")
                || value.contains("未检索候选")
                || value.contains("ACTIVITY_NOT_EXPOSED")
                || value.contains("SESSION_NOT_EXPOSED")
                || value.contains("MISSING_EVIDENCE");
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode parsed = objectMapper.readTree(json);
            return parsed == null ? objectMapper.createObjectNode() : parsed;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private double bool(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private record RuntimeFacts(
            boolean recommendationRouteSelected,
            boolean recommendationMainlineUsed,
            boolean planningRouteSelected,
            boolean planningMainlineUsed,
            boolean reactFallback,
            boolean evidenceViolation,
            int toolCalls,
            int retrievalCalls,
            int travelCalls,
            int planValidationCalls,
            int planValidationFailures,
            int planValidationPasses
    ) {
        boolean anyReactRouteSelected() {
            return recommendationRouteSelected || planningRouteSelected;
        }
    }
}

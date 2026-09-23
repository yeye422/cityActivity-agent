package com.city.service.evaluation;

import com.city.model.RequestTraceRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 从 request trace 提取 AgentScope ReAct 运行与质量指标。 */
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
                if (value != null) values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            });
        }
        Map<String, Double> result = new LinkedHashMap<>();
        values.forEach((name, metricValues) -> result.put(
                name, metricValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)));

        List<Double> latencies = safe.stream()
                .map(RequestTraceRow::getDurationMs)
                .filter(Objects::nonNull)
                .map(Number::doubleValue)
                .sorted()
                .toList();
        if (!latencies.isEmpty()) {
            result.put("latencyP50Ms", percentile(latencies, 0.50));
            result.put("latencyP95Ms", percentile(latencies, 0.95));
        }
        return result;
    }

    Map<String, Double> perTrace(RequestTraceRow trace) {
        RuntimeFacts facts = parse(trace);
        Map<String, Double> metrics = new LinkedHashMap<>();

        metrics.put("reactRouteCoverage", bool(facts.anyReactRouteSelected()));
        metrics.put("recommendationReactSuccessRate", facts.recommendationRouteSelected()
                ? bool(facts.recommendationCompleted()) : null);
        metrics.put("planningReactSuccessRate", facts.planningRouteSelected()
                ? bool(facts.planningCompleted()) : null);
        metrics.put("reactDegradationRate", facts.anyReactRouteSelected()
                ? bool(facts.reactDegraded()) : null);
        metrics.put("reactToolCallCount", facts.anyReactRouteSelected()
                ? (double) facts.toolCalls() : null);
        metrics.put("toolErrorRate", facts.toolCalls() > 0
                ? facts.toolErrors() / (double) facts.toolCalls() : null);

        metrics.put("retrievalToolCallCount", facts.recommendationRouteSelected()
                ? (double) facts.retrievalCalls() : null);
        metrics.put("reRetrievalRate", facts.recommendationRouteSelected()
                ? bool(facts.retrievalCalls() > 1) : null);
        metrics.put("userGoalCoverage", facts.recommendationRouteSelected()
                ? facts.userGoalCoverage() : null);
        metrics.put("candidateOutOfSetRate", facts.recommendationRouteSelected()
                ? bool(facts.candidateOutOfSet()) : null);

        metrics.put("travelToolCallCount", facts.planningRouteSelected()
                ? (double) facts.travelCalls() : null);
        metrics.put("planValidationCallCount", facts.planningRouteSelected()
                ? (double) facts.planValidationCalls() : null);
        metrics.put("planValidationFailureRate", facts.planningRouteSelected()
                ? bool(facts.planValidationFailures() > 0) : null);
        metrics.put("planRepairSuccessRate", facts.planValidationFailures() > 0
                ? bool(facts.planValidationPasses() > 0 && facts.planningCompleted()) : null);
        metrics.put("planValidRate", facts.planningRouteSelected()
                ? bool(facts.planningCompleted() && facts.planValidationPasses() > 0) : null);
        metrics.put("planValidationTimeConflictRate", facts.planningRouteSelected()
                ? bool(facts.timeConflictSeen()) : null);
        metrics.put("planValidationBudgetViolationRate", facts.planningRouteSelected()
                ? bool(facts.budgetViolationSeen()) : null);
        metrics.put("sessionHallucinationRate", facts.planningRouteSelected()
                ? bool(facts.sessionViolation()) : null);

        metrics.put("evidenceViolationRate", facts.anyReactRouteSelected()
                ? bool(facts.evidenceViolation()) : null);
        return metrics;
    }

    private RuntimeFacts parse(RequestTraceRow trace) {
        JsonNode root = readTree(trace == null ? null : trace.getTraceJson());
        JsonNode events = root.path("events");

        boolean recommendationRouteSelected = false;
        boolean recommendationCompleted = false;
        boolean planningRouteSelected = false;
        boolean planningCompleted = false;
        boolean reactDegraded = false;
        boolean evidenceViolation = false;
        boolean candidateOutOfSet = false;
        boolean sessionViolation = false;
        boolean timeConflictSeen = false;
        boolean budgetViolationSeen = false;
        int toolCalls = 0;
        int toolErrors = 0;
        int retrievalCalls = 0;
        int travelCalls = 0;
        int planValidationCalls = 0;
        int planValidationFailures = 0;
        int planValidationPasses = 0;
        Double userGoalCoverage = null;

        if (events.isArray()) {
            for (JsonNode event : events) {
                String type = event.path("eventType").asText("");
                String error = event.path("errorMessage").asText("");
                String input = event.path("inputPayload").asText("");
                String output = event.path("outputPayload").asText("");
                String combined = error + "\n" + input + "\n" + output;

                if ("RECOMMENDATION_REACT_ROUTE_SELECTED".equals(type)) recommendationRouteSelected = true;
                if ("RECOMMENDATION_REACT_COMPLETED".equals(type)) recommendationCompleted = true;
                if ("PLANNING_REACT_ROUTE_SELECTED".equals(type)) planningRouteSelected = true;
                if ("PLANNING_REACT_COMPLETED".equals(type)) planningCompleted = true;
                if ("RECOMMENDATION_REACT_FAILED".equals(type)
                        || "PLANNING_REACT_FAILED".equals(type)
                        || "RECOMMENDATION_DEGRADED".equals(type)
                        || "PLANNING_DEGRADED".equals(type)) reactDegraded = true;

                if (type.endsWith("_TOOL_CALLED")) toolCalls++;
                if (type.endsWith("_TOOL_FAILED") || "AGENT_TOOL_GUARD_BLOCKED".equals(type)) toolErrors++;
                if ("RETRIEVAL_TOOL_CALLED".equals(type)) retrievalCalls++;
                if ("TRAVEL_TIME_TOOL_CALLED".equals(type)) travelCalls++;
                if ("PLAN_VALIDATION_TOOL_CALLED".equals(type)) planValidationCalls++;
                if ("PLAN_VALIDATION_FAILED".equals(type)) planValidationFailures++;
                if ("PLAN_VALIDATION_PASSED".equals(type)) planValidationPasses++;

                if ("RECOMMENDATION_DECIDED".equals(type)) {
                    Double coverage = recommendationGoalCoverage(output);
                    if (coverage != null) userGoalCoverage = coverage;
                }

                if (containsEvidenceViolation(combined)) evidenceViolation = true;
                if (containsCandidateViolation(combined)) candidateOutOfSet = true;
                if (containsSessionViolation(combined)) sessionViolation = true;
                if (combined.contains("TIME_CONFLICT") || combined.contains("TRAVEL_TIME_CONFLICT")) {
                    timeConflictSeen = true;
                }
                if (combined.contains("BUDGET_EXCEEDED")) budgetViolationSeen = true;
            }
        }

        return new RuntimeFacts(
                recommendationRouteSelected,
                recommendationCompleted,
                planningRouteSelected,
                planningCompleted,
                reactDegraded,
                evidenceViolation,
                candidateOutOfSet,
                sessionViolation,
                timeConflictSeen,
                budgetViolationSeen,
                toolCalls,
                toolErrors,
                retrievalCalls,
                travelCalls,
                planValidationCalls,
                planValidationFailures,
                planValidationPasses,
                userGoalCoverage
        );
    }

    private Double recommendationGoalCoverage(String outputPayload) {
        JsonNode root = readTree(outputPayload);
        JsonNode decision = root.path("decision");
        JsonNode selectedNode = decision.path("selectedActivityIds");
        JsonNode assessments = decision.path("assessments");
        if (!selectedNode.isArray() || !assessments.isArray()) return null;

        Set<Long> selected = new LinkedHashSet<>();
        selectedNode.forEach(node -> {
            if (node.isNumber()) selected.add(node.asLong());
        });
        if (selected.isEmpty()) return null;

        double total = 0.0;
        int compared = 0;
        for (JsonNode assessment : assessments) {
            if (!assessment.path("activityId").isNumber()) continue;
            long activityId = assessment.path("activityId").asLong();
            if (!selected.contains(activityId)) continue;
            total += switch (assessment.path("goalFit").asText("").trim().toUpperCase()) {
                case "HIGH" -> 1.0;
                case "MEDIUM" -> 0.5;
                case "LOW" -> 0.0;
                default -> 0.0;
            };
            compared++;
        }
        return compared == 0 ? null : total / compared;
    }

    private boolean containsEvidenceViolation(String value) {
        return containsCandidateViolation(value)
                || containsSessionViolation(value)
                || value.contains("缺少当前 Run")
                || value.contains("MISSING_EVIDENCE");
    }

    private boolean containsCandidateViolation(String value) {
        if (value == null || value.isBlank()) return false;
        return value.contains("未登记 Activity Evidence")
                || value.contains("未检索候选")
                || value.contains("ACTIVITY_NOT_EXPOSED");
    }

    private boolean containsSessionViolation(String value) {
        if (value == null || value.isBlank()) return false;
        return value.contains("未登记 Session Evidence")
                || value.contains("SESSION_NOT_EXPOSED");
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.size() == 1) return sortedValues.getFirst();
        double rank = percentile * (sortedValues.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) return sortedValues.get(lower);
        double weight = rank - lower;
        return sortedValues.get(lower) * (1.0 - weight) + sortedValues.get(upper) * weight;
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
            boolean recommendationCompleted,
            boolean planningRouteSelected,
            boolean planningCompleted,
            boolean reactDegraded,
            boolean evidenceViolation,
            boolean candidateOutOfSet,
            boolean sessionViolation,
            boolean timeConflictSeen,
            boolean budgetViolationSeen,
            int toolCalls,
            int toolErrors,
            int retrievalCalls,
            int travelCalls,
            int planValidationCalls,
            int planValidationFailures,
            int planValidationPasses,
            Double userGoalCoverage
    ) {
        boolean anyReactRouteSelected() {
            return recommendationRouteSelected || planningRouteSelected;
        }
    }
}

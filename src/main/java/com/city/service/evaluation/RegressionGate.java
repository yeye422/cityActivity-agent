package com.city.service.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;

/** 纯函数式回归门禁，便于在不启动 Spring/LLM/数据库的情况下单元测试。 */
public final class RegressionGate {
    private static final double MAX_SCORE_DROP = -2.0;
    private static final double MAX_METRIC_DROP = -0.02;
    private static final String[] CRITICAL_METRICS = {
            "intentAccuracy", "slotAccuracy", "clarifyNecessityAccuracy",
            "hallucinationControl", "safetyCompliance", "operationAccuracy",
            "timeConstraintAccuracy", "multiTurnConsistency", "missingSlotAccuracy"
    };

    private RegressionGate() { }

    public static Result evaluate(Double baselineScore, Double currentScore,
                                  Map<String, Double> baselineMetrics,
                                  Map<String, Double> currentMetrics) {
        Map<String, Double> deltas = new LinkedHashMap<>();
        if (currentMetrics != null) {
            currentMetrics.forEach((name, value) -> {
                Double old = baselineMetrics == null ? null : baselineMetrics.get(name);
                if (old != null && value != null) {
                    double delta = value - old;
                    deltas.put(name, Math.round(delta * 1_000_000_000_000L) / 1_000_000_000_000.0);
                }
            });
        }
        double scoreDelta = baselineScore == null || currentScore == null ? 0.0 : currentScore - baselineScore;
        boolean passed = scoreDelta >= MAX_SCORE_DROP;
        for (String metric : CRITICAL_METRICS) {
            Double delta = deltas.get(metric);
            if (delta != null && delta < MAX_METRIC_DROP) {
                passed = false;
                break;
            }
        }
        return new Result(scoreDelta, deltas, passed);
    }

    public record Result(double scoreDelta, Map<String, Double> metricDeltas, boolean passed) { }
}

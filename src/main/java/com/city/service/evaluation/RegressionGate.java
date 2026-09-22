package com.city.service.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;

/** 纯函数式回归门禁，便于在不启动 Spring/LLM/数据库的情况下单元测试。 */
public final class RegressionGate {
    private static final double MAX_SCORE_DROP = -2.0;
    private static final double MAX_METRIC_DROP = -0.02;
    private static final double MAX_METRIC_INCREASE = 0.02;

    /** 越高越好的关键指标；下降超过 2% 判定回归。 */
    private static final String[] HIGHER_IS_BETTER_METRICS = {
            "intentAccuracy", "slotAccuracy", "clarifyNecessityAccuracy",
            "hallucinationControl", "safetyCompliance", "operationAccuracy",
            "timeConstraintAccuracy", "multiTurnConsistency", "missingSlotAccuracy",
            "recommendationReactSuccessRate", "planningReactSuccessRate"
    };

    /** 越低越好的 Agent 运行风险指标；上升超过 2% 判定回归。 */
    private static final String[] LOWER_IS_BETTER_METRICS = {
            "reactFallbackRate", "evidenceViolationRate"
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

        for (String metric : HIGHER_IS_BETTER_METRICS) {
            Double delta = deltas.get(metric);
            if (delta != null && delta < MAX_METRIC_DROP) {
                passed = false;
                break;
            }
        }

        if (passed) {
            for (String metric : LOWER_IS_BETTER_METRICS) {
                Double delta = deltas.get(metric);
                if (delta != null && delta > MAX_METRIC_INCREASE) {
                    passed = false;
                    break;
                }
            }
        }

        return new Result(scoreDelta, deltas, passed);
    }

    public record Result(double scoreDelta, Map<String, Double> metricDeltas, boolean passed) { }
}

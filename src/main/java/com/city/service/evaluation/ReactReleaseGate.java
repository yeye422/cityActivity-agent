package com.city.service.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReAct 专项评测的绝对门禁。
 *
 * <p>RegressionGate 负责“相对 Baseline 是否回退”；本门禁负责“第一次跑 react-v1 时本身是否够格”。
 * 这样即使还没有 Baseline，也不会因为所有请求都失败/降级 而被默认判定通过。</p>
 */
public final class ReactReleaseGate {
    static final double MIN_ROUTE_COVERAGE = 0.60;
    static final double MIN_RECOMMENDATION_SUCCESS = 0.80;
    static final double MIN_PLANNING_SUCCESS = 0.70;
    static final double MAX_DEGRADATION_RATE = 0.20;
    static final double MAX_EVIDENCE_VIOLATION_RATE = 0.0;

    private ReactReleaseGate() { }

    public static Result evaluate(Map<String, Double> metrics) {
        Map<String, Double> safe = metrics == null ? Map.of() : metrics;
        List<String> failures = new ArrayList<>();

        requireAtLeast(safe, "reactRouteCoverage", MIN_ROUTE_COVERAGE, failures);
        requireAtLeast(safe, "recommendationReactSuccessRate", MIN_RECOMMENDATION_SUCCESS, failures);
        requireAtLeast(safe, "planningReactSuccessRate", MIN_PLANNING_SUCCESS, failures);
        requireAtMost(safe, "reactDegradationRate", MAX_DEGRADATION_RATE, failures);
        requireAtMost(safe, "evidenceViolationRate", MAX_EVIDENCE_VIOLATION_RATE, failures);

        return new Result(failures.isEmpty(), List.copyOf(failures));
    }

    private static void requireAtLeast(Map<String, Double> metrics,
                                       String name,
                                       double threshold,
                                       List<String> failures) {
        Double value = metrics.get(name);
        if (value == null) {
            failures.add(name + " 缺失");
        } else if (value + 1e-12 < threshold) {
            failures.add(name + "=" + value + " < " + threshold);
        }
    }

    private static void requireAtMost(Map<String, Double> metrics,
                                      String name,
                                      double threshold,
                                      List<String> failures) {
        Double value = metrics.get(name);
        if (value == null) {
            failures.add(name + " 缺失");
        } else if (value - 1e-12 > threshold) {
            failures.add(name + "=" + value + " > " + threshold);
        }
    }

    public record Result(boolean passed, List<String> failures) { }
}

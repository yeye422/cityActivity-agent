package com.city.model;

import java.util.Map;

/** 固定评测集回归报告，保留本次报告并与进程内上一次运行做最小基线比较。 */
public record RegressionEvaluationReport(
        String evalSetVersion,
        EvaluationReport report,
        String baselineVersion,
        Double baselineScore,
        Double scoreDelta,
        Map<String, Double> metricDeltas,
        boolean passed
) {
}

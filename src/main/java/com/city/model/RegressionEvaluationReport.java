package com.city.model;

import java.util.Map;

/** 固定评测集回归报告，包含本次运行标识、版本指纹和显式 Baseline 比较结果。 */
public record RegressionEvaluationReport(
        String runId,
        String evalSetVersion,
        String evalSetHash,
        String gitCommit,
        String promptVersion,
        String ruleVersion,
        String modelVersion,
        EvaluationReport report,
        String baselineRunId,
        Double baselineScore,
        Double scoreDelta,
        Map<String, Double> metricDeltas,
        boolean passed
) {
}

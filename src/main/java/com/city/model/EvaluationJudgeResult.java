package com.city.model;

public record EvaluationJudgeResult(
        double explanationQuality,
        double naturalness,
        String reason
) {
}
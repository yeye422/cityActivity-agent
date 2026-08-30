package com.city.model;

import lombok.Data;

import java.time.LocalDateTime;

/** 一次固定评测运行的持久化记录，用于跨重启保留回归基线。 */
@Data
public class EvaluationRunRow {
    private Long id;
    private String runId;
    private Long userId;
    private String evalSetVersion;
    private String promptVersion;
    private String ruleVersion;
    private String modelVersion;
    private Integer totalTraces;
    private Double avgScore;
    private String metricSnapshot;
    private String baselineRunId;
    private Boolean passed;
    private LocalDateTime createdAt;
}

package com.city.model;

/** 将一次已完成的评测运行显式提升为当前评测集基线。 */
public record PromoteBaselineRequest(
        String runId,
        String baselineName
) {
}

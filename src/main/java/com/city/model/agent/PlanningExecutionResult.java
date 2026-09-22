package com.city.model.agent;

import com.city.model.PlanCandidate;

import java.util.List;

/**
 * PlanningWorker 的完整输出：既保留窗口级候选与证据，也携带 Java Solver 生成的合法方案候选。
 */
public record PlanningExecutionResult(
        PlanningResult planning,
        List<PlanCandidate> planCandidates
) {
    public PlanningExecutionResult {
        if (planning == null) {
            throw new IllegalArgumentException("planning 不能为空");
        }
        planCandidates = planCandidates == null ? List.of() : List.copyOf(planCandidates);
    }
}

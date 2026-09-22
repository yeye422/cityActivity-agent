package com.city.model.agent;

import com.city.model.PlanCandidate;

/** PlanningAgent 经最终 Java Solver 复核后的执行结果。 */
public record PlanningAgentExecutionResult(
        PlanningDecision decision,
        PlanCandidate acceptedPlan,
        PlanValidationResult finalValidation
) {
    public PlanningAgentExecutionResult {
        if (decision == null) throw new IllegalArgumentException("decision 不能为空");
        if (acceptedPlan == null) throw new IllegalArgumentException("acceptedPlan 不能为空");
        if (finalValidation == null || !finalValidation.valid()) {
            throw new IllegalArgumentException("finalValidation 必须为 valid");
        }
    }
}

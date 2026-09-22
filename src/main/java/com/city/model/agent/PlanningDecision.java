package com.city.model.agent;

/** PlanningAgent 的最终强类型输出；最终 plan 仍需由 Java 再执行一次 validate_plan 等价校验。 */
public record PlanningDecision(
        PlanProposal plan,
        String decisionSummary,
        double confidence
) {
    public PlanningDecision {
        if (plan == null || plan.items().isEmpty()) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        decisionSummary = decisionSummary == null ? "" : decisionSummary.trim();
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}

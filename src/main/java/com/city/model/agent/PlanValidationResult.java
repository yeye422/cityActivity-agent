package com.city.model.agent;

import com.city.model.PlanCandidate;

import java.util.List;

/** validate_plan Tool 的结构化结果；valid=false 时 violations 用于 PlanningAgent 定向修复。 */
public record PlanValidationResult(
        boolean valid,
        List<Violation> violations,
        PlanCandidate acceptedPlan
) {
    public PlanValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
        if (!valid) acceptedPlan = null;
    }

    public static PlanValidationResult valid(PlanCandidate acceptedPlan) {
        return new PlanValidationResult(true, List.of(), acceptedPlan);
    }

    public static PlanValidationResult invalid(List<Violation> violations) {
        return new PlanValidationResult(false, violations, null);
    }

    public record Violation(
            String code,
            String period,
            Long activityId,
            Long sessionId,
            String message,
            String repairHint
    ) {
        public Violation {
            code = code == null ? "UNKNOWN" : code.trim();
            period = period == null ? "" : period.trim();
            message = message == null ? "" : message.trim();
            repairHint = repairHint == null ? "" : repairHint.trim();
        }
    }
}

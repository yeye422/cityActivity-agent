package com.city.model.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** PlanningAgent 的最终强类型输出；最终 plan 仍由 Java 强制执行 validate_plan 等价校验。 */
@Data
@Accessors(fluent = true)
@NoArgsConstructor
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class PlanningDecision {
    private PlanProposal plan;
    private String decisionSummary;
    private double confidence;

    public PlanningDecision(PlanProposal plan, String decisionSummary, double confidence) {
        if (plan == null || plan.items() == null || plan.items().isEmpty()) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        this.plan = plan;
        this.decisionSummary = decisionSummary == null ? "" : decisionSummary.trim();
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}

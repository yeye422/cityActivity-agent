package com.city.model.context;

import com.city.model.TravelTimeEvidence;
import com.city.service.evidence.PlanningEvidenceRegistry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** AgentScope ToolExecutionContext 注入的模型不可见规划上下文。 */
public record PlanningToolContext(
        VerifiedRequestContext verifiedRequestContext,
        List<String> windows,
        PlanningEvidenceRegistry evidenceRegistry,
        BigDecimal maxBudget,
        List<TravelTimeEvidence> travelTimeEvidence
) {
    public PlanningToolContext {
        verifiedRequestContext = Objects.requireNonNull(verifiedRequestContext, "verifiedRequestContext");
        windows = windows == null ? List.of() : List.copyOf(windows);
        evidenceRegistry = Objects.requireNonNull(evidenceRegistry, "evidenceRegistry");
        travelTimeEvidence = travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);
    }
}

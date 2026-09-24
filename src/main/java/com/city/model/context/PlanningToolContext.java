package com.city.model.context;

import com.city.model.TravelTimeEvidence;
import com.city.model.agent.PlanNotebook;
import com.city.service.evidence.PlanningEvidenceRegistry;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Planning Java 边界共享的模型不可见规划上下文。 */
public record PlanningToolContext(
        VerifiedRequestContext verifiedRequestContext,
        List<String> windows,
        PlanningEvidenceRegistry evidenceRegistry,
        PlanNotebook notebook,
        BigDecimal maxBudget,
        List<TravelTimeEvidence> travelTimeEvidence
) {
    public PlanningToolContext {
        verifiedRequestContext = Objects.requireNonNull(verifiedRequestContext, "verifiedRequestContext");
        windows = windows == null ? List.of() : List.copyOf(windows);
        evidenceRegistry = Objects.requireNonNull(evidenceRegistry, "evidenceRegistry");
        notebook = Objects.requireNonNull(notebook, "notebook");
        travelTimeEvidence = travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);
    }

    /** 合并调用方已有路线证据和 Java 本轮补充的路线证据。 */
    public List<TravelTimeEvidence> allTravelTimeEvidence() {
        Map<RouteKey, TravelTimeEvidence> merged = new LinkedHashMap<>();
        for (TravelTimeEvidence evidence : travelTimeEvidence) {
            if (evidence == null) continue;
            merged.put(new RouteKey(evidence.fromVenueId(), evidence.toVenueId()), evidence);
        }
        for (TravelTimeEvidence evidence : evidenceRegistry.travelTimeEvidence()) {
            if (evidence == null) continue;
            merged.put(new RouteKey(evidence.fromVenueId(), evidence.toVenueId()), evidence);
        }
        return List.copyOf(new ArrayList<>(merged.values()));
    }

    private record RouteKey(Long fromVenueId, Long toVenueId) {}
}

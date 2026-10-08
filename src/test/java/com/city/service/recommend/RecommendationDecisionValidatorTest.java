package com.city.service.recommend;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.agent.RecommendationDecision;
import com.city.model.tool.RetrievalToolResult;
import com.city.service.evidence.CandidateEvidenceRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecommendationDecisionValidatorTest {

    private final RecommendationDecisionValidator validator = new RecommendationDecisionValidator();

    @Test
    void shouldAcceptOnlyCandidatesExposedByRetrievalTool() {
        CandidateEvidenceRegistry registry = registryWith(101L, 202L);
        RecommendationDecision decision = new RecommendationDecision(
                List.of(101L, 202L),
                List.of(
                        new RecommendationDecision.CandidateAssessment(101L, "HIGH", "互动性和新鲜感更高"),
                        new RecommendationDecision.CandidateAssessment(202L, "MEDIUM", "更轻松")
                ),
                true,
                "优先互动与新鲜感",
                1.2
        );

        RecommendationDecision validated = validator.validate(decision, registry);

        assertEquals(List.of(101L, 202L), validated.selectedActivityIds());
        assertEquals(List.of(101L, 202L), registry.resolveSelected(validated.selectedActivityIds())
                .stream().map(ActivityItem::id).toList());
        assertEquals(1.0, validated.confidence());
    }

    @Test
    void shouldRejectHallucinatedCandidateId() {
        CandidateEvidenceRegistry registry = registryWith(101L);
        RecommendationDecision decision = new RecommendationDecision(
                List.of(999L),
                List.of(),
                true,
                "",
                0.8
        );

        assertThrows(IllegalStateException.class, () -> validator.validate(decision, registry));
    }

    @Test
    void shouldRejectFinalDecisionWhenPoolStillMarkedInsufficient() {
        CandidateEvidenceRegistry registry = registryWith(101L);
        RecommendationDecision decision = new RecommendationDecision(
                List.of(101L),
                List.of(),
                false,
                "",
                0.5
        );

        assertThrows(IllegalStateException.class, () -> validator.validate(decision, registry));
    }

    private CandidateEvidenceRegistry registryWith(Long... ids) {
        CandidateEvidenceRegistry registry = new CandidateEvidenceRegistry(2);
        registry.beginRetrieval("test");
        List<ActivityItem> activities = Arrays.stream(ids)
                .map(id -> new ActivityItem(
                        id, SourceMode.PUBLIC, null, "candidate-" + id,
                        null, null, null, null, null, null, 0.9))
                .toList();
        List<RetrievalToolResult.Candidate> candidates = activities.stream()
                .map(item -> new RetrievalToolResult.Candidate(
                        item.id(), item.name(), item.slots(), item.validFrom(), item.validTo(),
                        item.validStartTime(), item.validEndTime(), item.durationMinutes(), item.matchScore()))
                .toList();
        registry.recordResult(new RetrievalToolResult("test", candidates), activities);
        return registry;
    }
}

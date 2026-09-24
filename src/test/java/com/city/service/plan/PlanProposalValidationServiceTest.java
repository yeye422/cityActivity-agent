package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.TravelTimeEvidence;
import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanValidationResult;
import com.city.service.evidence.PlanningEvidenceRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanProposalValidationServiceTest {

    private final PlanProposalValidationService service = new PlanProposalValidationService();

    @Test
    void shouldAcceptProposalBackedByEvidenceAndSolver() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item("14:00-16:00", 101L, 1001L),
                new PlanProposal.Item("18:00-20:00", 202L, 2002L)
        ));

        PlanValidationResult result = service.validate(
                proposal,
                registry,
                BigDecimal.valueOf(300),
                List.of(new TravelTimeEvidence(101L, 202L, 30, "TEST"))
        );

        assertTrue(result.valid());
        assertNotNull(result.acceptedPlan());
        assertEquals(2, result.acceptedPlan().items().size());
    }

    @Test
    void shouldRequireAgentToChooseExactSessionWhenSessionsAreExposed() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item("14:00-16:00", 101L, null)
        ));

        PlanValidationResult result = service.validate(
                proposal,
                registry,
                BigDecimal.valueOf(300),
                List.of()
        );

        assertFalse(result.valid());
        assertEquals("SESSION_REQUIRED", result.violations().getFirst().code());
    }

    @Test
    void shouldRequireTravelEvidenceForDifferentVenues() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item("14:00-16:00", 101L, 1001L),
                new PlanProposal.Item("18:00-20:00", 202L, 2002L)
        ));

        PlanValidationResult result = service.validate(proposal, registry, null, List.of());

        assertFalse(result.valid());
        assertEquals("MISSING_TRAVEL_EVIDENCE", result.violations().getFirst().code());
    }

    @Test
    void shouldRejectActivityNeverExposedForWindow() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item("14:00-16:00", 999L, null)
        ));

        PlanValidationResult result = service.validate(proposal, registry, null, List.of());

        assertFalse(result.valid());
        assertEquals("ACTIVITY_NOT_EXPOSED", result.violations().getFirst().code());
    }

    @Test
    void shouldRejectTimeConflictEvenWhenAllIdsAndTravelEvidenceAreValid() {
        PlanningEvidenceRegistry registry = registry(true);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item("14:00-16:00", 101L, 1001L),
                new PlanProposal.Item("18:00-20:00", 202L, 2002L)
        ));

        PlanValidationResult result = service.validate(
                proposal,
                registry,
                null,
                List.of(new TravelTimeEvidence(101L, 202L, 30, "TEST"))
        );

        assertFalse(result.valid());
        assertEquals("TIME_CONFLICT", result.violations().getFirst().code());
    }

    @Test
    void shouldReturnStructuredBudgetViolation() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item("14:00-16:00", 101L, 1001L),
                new PlanProposal.Item("18:00-20:00", 202L, 2002L)
        ));

        PlanValidationResult result = service.validate(
                proposal,
                registry,
                BigDecimal.valueOf(200),
                List.of(new TravelTimeEvidence(101L, 202L, 30, "TEST"))
        );

        assertFalse(result.valid());
        assertEquals("BUDGET_EXCEEDED", result.violations().getFirst().code());
    }

    private PlanningEvidenceRegistry registry(boolean overlapping) {
        ActivityItem first = activity(101L, "陶艺");
        ActivityItem second = activity(202L, "桌游");
        ActivitySessionResponse firstSession = session(
                1001L, 101L,
                LocalDateTime.of(2026, 9, 26, 14, 0),
                LocalDateTime.of(2026, 9, 26, overlapping ? 19 : 16, 0),
                BigDecimal.valueOf(120)
        );
        ActivitySessionResponse secondSession = session(
                2002L, 202L,
                LocalDateTime.of(2026, 9, 26, 18, 0),
                LocalDateTime.of(2026, 9, 26, 20, 0),
                BigDecimal.valueOf(100)
        );

        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        registry.record(List.of(
                new ActivityPlanService.PlannedActivity(
                        "14:00-16:00", first, SlotBundle.empty(), List.of(first),
                        Map.of(101L, List.of(firstSession)), firstSession),
                new ActivityPlanService.PlannedActivity(
                        "18:00-20:00", second, SlotBundle.empty(), List.of(second),
                        Map.of(202L, List.of(secondSession)), secondSession)
        ));
        return registry;
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 120, 0.9
        );
    }

    private ActivitySessionResponse session(Long sessionId,
                                            Long activityId,
                                            LocalDateTime start,
                                            LocalDateTime end,
                                            BigDecimal price) {
        return new ActivitySessionResponse(
                sessionId,
                activityId,
                activityId,
                "场地-" + activityId,
                "INDOOR",
                "上海",
                "浦东",
                "测试地址",
                start,
                end,
                price,
                10,
                "OPEN",
                null,
                null
        );
    }
}

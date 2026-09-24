package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.TravelTimeEvidence;
import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanValidationResult;
import com.city.model.context.PlanningHorizon;
import com.city.service.evidence.PlanningEvidenceRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanProposalValidationServiceTest {

    private final PlanProposalValidationService service = new PlanProposalValidationService();

    @Test
    void shouldAcceptExactAgentSelectedProposalBackedByEvidence() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item(101L, 1001L),
                new PlanProposal.Item(202L, 2002L)
        ));

        PlanValidationResult result = service.validate(
                proposal, registry, horizon(), BigDecimal.valueOf(300),
                List.of(new TravelTimeEvidence(101L, 202L, 30, "TEST")));

        assertTrue(result.valid());
        assertNotNull(result.acceptedPlan());
        assertEquals(2, result.acceptedPlan().items().size());
    }

    @Test
    void shouldAcceptFlexibleActivityWithIsoPlannedTimeInsideHorizon() {
        ActivityItem flexible = activity(303L, "城市漫步");
        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        registry.record(List.of(new ActivityPlanService.CandidateBatch(
                horizon().ranges().getFirst(),
                List.of(flexible),
                Map.of()
        )));

        PlanValidationResult result = service.validate(
                new PlanProposal(List.of(new PlanProposal.Item(
                        303L,
                        null,
                        "2026-09-27T10:00:00",
                        "2026-09-27T12:00:00"
                ))),
                registry,
                horizon(),
                null,
                List.of()
        );

        assertTrue(result.valid());
        assertEquals(LocalDateTime.of(2026, 9, 27, 10, 0),
                result.acceptedPlan().items().getFirst().startAt());
    }

    @Test
    void shouldRequireAgentToChooseExactSessionWhenSessionsAreExposed() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanValidationResult result = service.validate(
                new PlanProposal(List.of(new PlanProposal.Item(101L, null))),
                registry, horizon(), BigDecimal.valueOf(300), List.of());

        assertFalse(result.valid());
        assertEquals("SESSION_REQUIRED", result.violations().getFirst().code());
    }

    @Test
    void shouldRequireTravelEvidenceForDifferentVenues() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item(101L, 1001L),
                new PlanProposal.Item(202L, 2002L)
        ));

        PlanValidationResult result = service.validate(proposal, registry, horizon(), null, List.of());

        assertFalse(result.valid());
        assertEquals("MISSING_TRAVEL_EVIDENCE", result.violations().getFirst().code());
        String hint = result.violations().getFirst().repairHint();
        assertTrue(hint.contains("get_travel_time("));
        assertTrue(hint.contains("fromActivityId=101"));
        assertTrue(hint.contains("fromSessionId=1001"));
        assertTrue(hint.contains("toActivityId=202"));
        assertTrue(hint.contains("toSessionId=2002"));
    }

    @Test
    void shouldRejectActivityNeverExposedInCurrentRun() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanValidationResult result = service.validate(
                new PlanProposal(List.of(new PlanProposal.Item(
                        999L, null,
                        LocalDateTime.of(2026, 9, 27, 14, 0),
                        LocalDateTime.of(2026, 9, 27, 16, 0)))),
                registry, horizon(), null, List.of());

        assertFalse(result.valid());
        assertEquals("ACTIVITY_NOT_EXPOSED", result.violations().getFirst().code());
    }

    @Test
    void shouldRejectTimeConflictEvenWhenIdsAndTravelEvidenceAreValid() {
        PlanningEvidenceRegistry registry = registry(true);
        PlanValidationResult result = service.validate(
                new PlanProposal(List.of(
                        new PlanProposal.Item(101L, 1001L),
                        new PlanProposal.Item(202L, 2002L))),
                registry, horizon(), null,
                List.of(new TravelTimeEvidence(101L, 202L, 30, "TEST")));

        assertFalse(result.valid());
        assertEquals("TIME_CONFLICT", result.violations().getFirst().code());
    }

    @Test
    void shouldRejectSessionOutsidePlanningHorizon() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanningHorizon shortHorizon = new PlanningHorizon(List.of(new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, 15, 0),
                LocalDateTime.of(2026, 9, 27, 23, 0)
        )));
        PlanValidationResult result = service.validate(
                new PlanProposal(List.of(new PlanProposal.Item(101L, 1001L))),
                registry, shortHorizon, null, List.of());

        assertFalse(result.valid());
        assertEquals("OUTSIDE_PLANNING_HORIZON", result.violations().getFirst().code());
    }

    @Test
    void shouldReturnStructuredBudgetViolation() {
        PlanningEvidenceRegistry registry = registry(false);
        PlanValidationResult result = service.validate(
                new PlanProposal(List.of(
                        new PlanProposal.Item(101L, 1001L),
                        new PlanProposal.Item(202L, 2002L))),
                registry, horizon(), BigDecimal.valueOf(200),
                List.of(new TravelTimeEvidence(101L, 202L, 30, "TEST")));

        assertFalse(result.valid());
        assertEquals("BUDGET_EXCEEDED", result.violations().getFirst().code());
    }

    private PlanningHorizon horizon() {
        return new PlanningHorizon(List.of(new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, 8, 0),
                LocalDateTime.of(2026, 9, 27, 23, 0)
        )));
    }

    private PlanningEvidenceRegistry registry(boolean overlapping) {
        ActivityItem first = activity(101L, "陶艺");
        ActivityItem second = activity(202L, "桌游");
        ActivitySessionResponse firstSession = session(
                1001L, 101L, 101L,
                LocalDateTime.of(2026, 9, 27, 14, 0),
                LocalDateTime.of(2026, 9, 27, overlapping ? 19 : 16, 0),
                BigDecimal.valueOf(120));
        ActivitySessionResponse secondSession = session(
                2002L, 202L, 202L,
                LocalDateTime.of(2026, 9, 27, 18, 0),
                LocalDateTime.of(2026, 9, 27, 20, 0),
                BigDecimal.valueOf(100));

        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        registry.record(List.of(new ActivityPlanService.CandidateBatch(
                horizon().ranges().getFirst(),
                List.of(first, second),
                Map.of(
                        101L, List.of(firstSession),
                        202L, List.of(secondSession)))));
        return registry;
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 120, 0.9);
    }

    private ActivitySessionResponse session(Long sessionId,
                                            Long activityId,
                                            Long venueId,
                                            LocalDateTime start,
                                            LocalDateTime end,
                                            BigDecimal price) {
        return new ActivitySessionResponse(
                sessionId, activityId, venueId, "场地-" + venueId, "INDOOR",
                "西安", "高新", "测试地址", start, end, price,
                10, "OPEN", null, null);
    }
}

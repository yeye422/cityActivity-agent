package com.city.model.agent;

import com.city.model.context.PlanningHorizon;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanNotebookTest {

    @Test
    void shouldTrackRepeatedValidateRepairLifecycleUntilValid() {
        PlanningHorizon.Range range = range();
        PlanNotebook notebook = new PlanNotebook(new PlanningHorizon(List.of(range)));
        notebook.recordDiscovery(List.of(range));
        assertEquals(PlanNotebook.Status.READY_TO_PROPOSE, notebook.status());

        PlanProposal first = proposal(11L);
        assertFalse(notebook.beginValidation(first));
        notebook.completeValidation(invalid("TIME_CONFLICT", 11L));

        PlanProposal second = proposal(12L);
        assertTrue(notebook.beginValidation(second));
        notebook.completeValidation(invalid("TRAVEL_TIME_CONFLICT", 12L));

        PlanProposal third = proposal(13L);
        assertTrue(notebook.beginValidation(third));
        notebook.completeValidation(invalid("BUDGET_EXCEEDED", 13L));

        PlanProposal fourth = proposal(14L);
        assertTrue(notebook.beginValidation(fourth));
        notebook.completeValidation(PlanValidationResult.valid(null));

        PlanNotebook.Snapshot snapshot = notebook.snapshot();
        assertEquals(PlanNotebook.Status.VALIDATED, snapshot.status());
        assertEquals(4, snapshot.validationAttempts());
        assertEquals(3, snapshot.repairAttempts());
        assertEquals(List.of(range), snapshot.discoveredRanges());
        assertTrue(snapshot.latestViolations().isEmpty());
    }

    private PlanningHorizon.Range range() {
        return new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, 8, 0),
                LocalDateTime.of(2026, 9, 27, 23, 0));
    }

    private PlanProposal proposal(Long sessionId) {
        return new PlanProposal(List.of(new PlanProposal.Item(1L, sessionId)));
    }

    private PlanValidationResult invalid(String code, Long sessionId) {
        return PlanValidationResult.invalid(List.of(
                new PlanValidationResult.Violation(
                        code, "", 1L, sessionId, "校验失败", "重新选择真实候选")));
    }
}

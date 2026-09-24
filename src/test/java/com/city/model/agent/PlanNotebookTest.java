package com.city.model.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanNotebookTest {

    @Test
    void shouldTrackRepeatedValidateRepairLifecycleUntilValid() {
        PlanNotebook notebook = new PlanNotebook(List.of("AFTERNOON", "EVENING"));
        notebook.recordDiscovery(List.of("AFTERNOON", "EVENING"));
        assertEquals(PlanNotebook.Status.READY_TO_PROPOSE, notebook.status());

        PlanProposal first = proposal(11L);
        assertFalse(notebook.beginValidation(first));
        notebook.completeValidation(invalid("TIME_CONFLICT", 11L));
        assertEquals(PlanNotebook.Status.REPAIR_REQUIRED, notebook.status());

        PlanProposal second = proposal(12L);
        assertTrue(notebook.beginValidation(second));
        notebook.completeValidation(invalid("TRAVEL_TIME_CONFLICT", 12L));
        assertEquals(PlanNotebook.Status.REPAIR_REQUIRED, notebook.status());

        PlanProposal third = proposal(13L);
        assertTrue(notebook.beginValidation(third));
        notebook.completeValidation(invalid("BUDGET_EXCEEDED", 13L));
        assertEquals(PlanNotebook.Status.REPAIR_REQUIRED, notebook.status());

        PlanProposal fourth = proposal(14L);
        assertTrue(notebook.beginValidation(fourth));
        notebook.completeValidation(PlanValidationResult.valid(null));

        PlanNotebook.Snapshot snapshot = notebook.snapshot();
        assertEquals(PlanNotebook.Status.VALIDATED, snapshot.status());
        assertEquals(4, snapshot.validationAttempts());
        assertEquals(3, snapshot.repairAttempts());
        assertTrue(snapshot.latestViolations().isEmpty());
        assertTrue(notebook.validated());
    }

    private PlanProposal proposal(Long sessionId) {
        return new PlanProposal(List.of(
                new PlanProposal.Item("AFTERNOON", 1L, sessionId)
        ));
    }

    private PlanValidationResult invalid(String code, Long sessionId) {
        return PlanValidationResult.invalid(List.of(
                new PlanValidationResult.Violation(
                        code,
                        "AFTERNOON",
                        1L,
                        sessionId,
                        "校验失败",
                        "重新选择真实候选"
                )
        ));
    }
}

package com.city.model.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanNotebookTest {

    @Test
    void shouldTrackValidateRepairValidateLifecycle() {
        PlanNotebook notebook = new PlanNotebook(List.of("AFTERNOON", "EVENING"));
        notebook.recordDiscovery(List.of("AFTERNOON", "EVENING"));
        assertEquals(PlanNotebook.Status.READY_TO_PROPOSE, notebook.status());

        PlanProposal first = new PlanProposal(List.of(
                new PlanProposal.Item("AFTERNOON", 1L, 11L)
        ));
        assertFalse(notebook.beginValidation(first));
        notebook.completeValidation(PlanValidationResult.invalid(List.of(
                new PlanValidationResult.Violation(
                        "TIME_CONFLICT", "AFTERNOON", 1L, 11L, "冲突", "换场次")
        )));
        assertEquals(PlanNotebook.Status.REPAIR_REQUIRED, notebook.status());

        PlanProposal repaired = new PlanProposal(List.of(
                new PlanProposal.Item("AFTERNOON", 1L, 12L)
        ));
        assertTrue(notebook.beginValidation(repaired));
        notebook.completeValidation(PlanValidationResult.valid(null));

        PlanNotebook.Snapshot snapshot = notebook.snapshot();
        assertEquals(PlanNotebook.Status.VALIDATED, snapshot.status());
        assertEquals(2, snapshot.validationAttempts());
        assertEquals(1, snapshot.repairAttempts());
        assertTrue(snapshot.latestViolations().isEmpty());
        assertTrue(notebook.validated());
    }
}

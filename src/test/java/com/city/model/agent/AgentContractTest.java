package com.city.model.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentContractTest {
    @Test
    void taskShouldDefensivelyCopyMutableInputs() {
        HashMap<String, Object> context = new HashMap<>();
        context.put("city", "西安");
        HashSet<ToolCapability> capabilities = new HashSet<>();
        capabilities.add(ToolCapability.ACTIVITY_SEARCH);
        ArrayList<EvidenceRef> evidence = new ArrayList<>();

        AgentTask task = new AgentTask("task-1", "session-1",
                AgentTaskType.ACTIVITY_DISCOVERY, true, context, capabilities, evidence, Instant.now());
        context.clear();
        capabilities.clear();

        assertEquals("西安", task.contextSnapshot().get("city"));
        assertEquals(1, task.allowedCapabilities().size());
        assertNull(task.traceId());
        assertEquals(ExecutionBudget.inheritDefaults(), task.executionBudget());
    }

    @Test
    void taskShouldCarryRuntimeLineageAndBudget() {
        ExecutionBudget budget = new ExecutionBudget(2, 5, 8000L, 1500L);
        AgentTask task = new AgentTask(
                "task-runtime", "session-1", AgentTaskType.ACTIVITY_DISCOVERY, true,
                java.util.Map.of(), java.util.Set.of(ToolCapability.ACTIVITY_SEARCH), java.util.List.of(),
                Instant.now().plusSeconds(30),
                " trace-1 ", "run-1", "parent-1", budget);

        assertEquals("trace-1", task.traceId());
        assertEquals("run-1", task.runId());
        assertEquals("parent-1", task.parentTaskId());
        assertEquals(budget, task.executionBudget());
    }

    @Test
    void executionBudgetMustRejectNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionBudget(0, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionBudget(null, null, -1L, null));
    }

    @Test
    void evidenceMustHaveAuthorityAndIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceRef(null, "id", "hash", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceRef(EvidenceType.ACTIVITY, "", "hash", Instant.now()));
    }

    @Test
    void verifiedEntityMustHaveEvidenceReference() {
        assertThrows(IllegalArgumentException.class, () -> new AgentResult(
                AgentResult.Status.COMPLETED, "ok", java.util.Set.of(1L), java.util.Set.of(),
                java.util.List.of(), java.util.List.of(), java.util.Map.of()));

        AgentResult result = new AgentResult(
                AgentResult.Status.COMPLETED, "ok", java.util.Set.of(1L), java.util.Set.of(),
                java.util.List.of(new EvidenceRef(EvidenceType.ACTIVITY, "1", "hash", Instant.now())),
                java.util.List.of(), java.util.Map.of());
        assertEquals(java.util.Set.of(1L), result.verifiedActivityIds());
    }

    @Test
    void readOnlyTaskMustRejectWriteCapability() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTask(
                "task-2", "session-1", AgentTaskType.MEMORY_MAINTENANCE, true,
                java.util.Map.of(), java.util.Set.of(ToolCapability.PREFERENCE_WRITE),
                java.util.List.of(), Instant.now()));
    }
}

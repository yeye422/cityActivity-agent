package com.city.service.worker;

import com.city.model.agent.AgentTask;
import com.city.model.agent.AgentTaskType;
import com.city.model.agent.ToolCapability;
import com.city.service.agent.ToolContractRegistry;
import com.city.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WorkerDispatcherTest {

    @Test
    void shouldExecuteValidTaskAndKeepStrongTypedResult() {
        WorkerDispatcher dispatcher = new WorkerDispatcher(
                new ToolContractRegistry(), mock(AgentTraceService.class));
        AgentTask task = task(AgentTaskType.CONTEXT_UNDERSTANDING,
                Set.of(ToolCapability.CONTEXT_PARSE), Instant.now().plusSeconds(30));

        String result = dispatcher.dispatch(task, () -> "ok");

        assertEquals("ok", result);
    }

    @Test
    void shouldRejectCapabilityBeforeWorkerExecution() {
        WorkerDispatcher dispatcher = new WorkerDispatcher(
                new ToolContractRegistry(), mock(AgentTraceService.class));
        AgentTask task = task(AgentTaskType.RESPONSE_GENERATION,
                Set.of(ToolCapability.CONTEXT_PARSE), Instant.now().plusSeconds(30));

        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch(task, () -> "should-not-run"));
    }

    @Test
    void shouldRejectExpiredTask() {
        WorkerDispatcher dispatcher = new WorkerDispatcher(
                new ToolContractRegistry(), mock(AgentTraceService.class));
        AgentTask task = task(AgentTaskType.CONTEXT_UNDERSTANDING,
                Set.of(ToolCapability.CONTEXT_PARSE), Instant.now().minusSeconds(1));

        assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatch(task, () -> "should-not-run"));
    }

    private AgentTask task(AgentTaskType type,
                           Set<ToolCapability> capabilities,
                           Instant deadline) {
        return new AgentTask(
                "task_test",
                "sess_test",
                type,
                true,
                Map.of(),
                capabilities,
                List.of(),
                deadline
        );
    }
}

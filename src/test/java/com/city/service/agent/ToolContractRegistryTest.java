package com.city.service.agent;

import com.city.model.agent.AgentTask;
import com.city.model.agent.AgentTaskType;
import com.city.model.agent.ToolCapability;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolContractRegistryTest {
    private final ToolContractRegistry registry = new ToolContractRegistry();

    @Test
    void discoveryWorkerMayUseOnlyRegisteredReadCapabilities() {
        AgentTask task = new AgentTask(
                "task-1", "session-1", AgentTaskType.ACTIVITY_DISCOVERY, true,
                Map.of(), Set.of(ToolCapability.ACTIVITY_SEARCH, ToolCapability.WEATHER_READ),
                List.of(), Instant.now().plusSeconds(10));
        assertDoesNotThrow(() -> registry.validate(task));
    }

    @Test
    void responseWorkerCannotUseActivitySearch() {
        AgentTask task = new AgentTask(
                "task-2", "session-1", AgentTaskType.RESPONSE_GENERATION, true,
                Map.of(), Set.of(ToolCapability.ACTIVITY_SEARCH), List.of(), Instant.now().plusSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> registry.validate(task));
    }
}

package com.city.service.trace;

import com.city.mapper.AgentTraceMapper;
import com.city.model.RequestTraceRow;
import com.city.model.AgentUiEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTraceServiceTest {

    @Test
    void shouldTreatRecoverablePlanValidationFailureAsStepInsteadOfRunError() {
        assertEquals(
                AgentUiEventType.STEP_COMPLETED,
                AgentTraceService.toUiEventType("PLAN_VALIDATION_FAILED", null)
        );
        assertEquals(
                AgentUiEventType.ERROR,
                AgentTraceService.toUiEventType("PLANNING_AGENT_FAILED", null)
        );
        assertEquals(
                AgentUiEventType.ERROR,
                AgentTraceService.toUiEventType("PLAN_VALIDATION_FAILED", "solver crashed")
        );
    }

    @Test
    void shouldPersistSemanticVersionsAndGitCommit() throws Exception {
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        BuildVersionService buildVersionService = mock(BuildVersionService.class);
        when(buildVersionService.gitCommit()).thenReturn("abc1234");
        ObjectMapper objectMapper = new ObjectMapper();

        AgentTraceService service = new AgentTraceService(
                mapper,
                objectMapper,
                buildVersionService,
                "v2",
                "v2"
        );

        try (AgentTraceService.TraceScope ignored = service.openTrace("trace_test", "sess_test", 1L)) {
            service.recordEvent("REQUEST_RECEIVED", "HTTP", "input", "output");
        }

        ArgumentCaptor<RequestTraceRow> captor = ArgumentCaptor.forClass(RequestTraceRow.class);
        verify(mapper).insert(captor.capture());
        JsonNode trace = objectMapper.readTree(captor.getValue().getTraceJson());

        assertEquals("v2", trace.get("promptVersion").asText());
        assertEquals("v2", trace.get("ruleVersion").asText());
        assertEquals("abc1234", trace.get("gitCommit").asText());
    }
}

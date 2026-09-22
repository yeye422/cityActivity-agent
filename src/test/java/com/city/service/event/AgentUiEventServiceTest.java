package com.city.service.event;

import com.city.mapper.AgentUiEventMapper;
import com.city.model.AgentUiEvent;
import com.city.model.AgentUiEventRow;
import com.city.model.AgentUiEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentUiEventServiceTest {

    @Test
    void shouldReplayOnlyEventsAfterLastEventIdAcrossTraceBoundary() {
        AgentUiEventService service = new AgentUiEventService();
        service.publish(1L, "s1", event("trace-a", 1));
        service.publish(1L, "s1", event("trace-a", 2));
        service.publish(1L, "s1", event("trace-b", 1));

        List<AgentUiEvent> replay = service.replayAfter(1L, "s1", "trace-a:2");

        assertEquals(1, replay.size());
        assertEquals("trace-b", replay.getFirst().traceId());
        assertEquals(1, replay.getFirst().sequence());
    }

    @Test
    void shouldNotReplayOldHistoryForFreshSubscriptionWithoutCursor() {
        AgentUiEventService service = new AgentUiEventService();
        service.publish(1L, "s1", event("trace-a", 1));

        assertTrue(service.replayAfter(1L, "s1", null).isEmpty());
        assertTrue(service.replayAfter(1L, "s1", " ").isEmpty());
    }

    @Test
    void shouldReplayRetainedWindowWhenCursorIsOlderThanHistory() {
        AgentUiEventService service = new AgentUiEventService();
        for (int i = 1; i <= AgentUiEventService.MAX_HISTORY_PER_SESSION + 5; i++) {
            service.publish(1L, "s1", event("trace-a", i));
        }

        List<AgentUiEvent> replay = service.replayAfter(1L, "s1", "trace-a:1");

        assertEquals(AgentUiEventService.MAX_HISTORY_PER_SESSION, replay.size());
        assertEquals(6, replay.getFirst().sequence());
    }

    @Test
    void shouldKeepUsersAndSessionsIsolated() {
        AgentUiEventService service = new AgentUiEventService();
        service.publish(1L, "s1", event("trace-a", 1));
        service.publish(2L, "s1", event("trace-b", 1));
        service.publish(1L, "s2", event("trace-c", 1));

        assertEquals("trace-a",
                service.replayAfter(1L, "s1", "bad-cursor").getFirst().traceId());
        assertEquals("trace-b",
                service.replayAfter(2L, "s1", "bad-cursor").getFirst().traceId());
        assertEquals("trace-c",
                service.replayAfter(1L, "s2", "bad-cursor").getFirst().traceId());
    }

    @Test
    void shouldPersistEventBeforeKeepingHotReplayHistory() {
        AgentUiEventMapper mapper = mock(AgentUiEventMapper.class);
        AgentUiEventService service = new AgentUiEventService(
                mapper, new ObjectMapper().findAndRegisterModules());
        AgentUiEvent event = event("trace-a", 7);

        service.publish(1L, "s1", event);

        verify(mapper).insertIgnore(any(AgentUiEventRow.class));
        assertEquals(event, service.replayAfter(1L, "s1", "bad-cursor").getFirst());
    }

    @Test
    void shouldReplayPersistedEventsAfterCursorEvenWithoutLocalHistory() throws Exception {
        AgentUiEventMapper mapper = mock(AgentUiEventMapper.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentUiEvent expected = event("trace-b", 1);
        AgentUiEventRow row = new AgentUiEventRow();
        row.setId(101L);
        row.setUserId(1L);
        row.setSessionId("s1");
        row.setTraceId(expected.traceId());
        row.setEventSeq(expected.sequence());
        row.setEventJson(objectMapper.writeValueAsString(expected));

        when(mapper.findCursorId(1L, "s1", "trace-a", 2)).thenReturn(100L);
        when(mapper.findAfterId(1L, "s1", 100L, AgentUiEventService.MAX_HISTORY_PER_SESSION))
                .thenReturn(List.of(row));

        AgentUiEventService service = new AgentUiEventService(mapper, objectMapper);
        List<AgentUiEvent> replay = service.replayAfter(1L, "s1", "trace-a:2");

        assertEquals(List.of(expected), replay);
    }

    @Test
    void shouldFallbackToMemoryWhenDurableReplayFails() {
        AgentUiEventMapper mapper = mock(AgentUiEventMapper.class);
        when(mapper.findCursorId(1L, "s1", "trace-a", 1))
                .thenThrow(new IllegalStateException("table unavailable"));
        AgentUiEventService service = new AgentUiEventService(
                mapper, new ObjectMapper().findAndRegisterModules());
        AgentUiEvent first = event("trace-a", 1);
        AgentUiEvent second = event("trace-a", 2);
        service.publish(1L, "s1", first);
        service.publish(1L, "s1", second);

        List<AgentUiEvent> replay = service.replayAfter(1L, "s1", "trace-a:1");

        assertEquals(List.of(second), replay);
    }

    private AgentUiEvent event(String traceId, int sequence) {
        return new AgentUiEvent(
                AgentUiEventType.STEP_COMPLETED,
                traceId,
                "s1",
                sequence,
                "TEST",
                "TEST_EVENT",
                null,
                Instant.now()
        );
    }
}

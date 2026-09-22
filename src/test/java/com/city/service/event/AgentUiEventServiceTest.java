package com.city.service.event;

import com.city.model.AgentUiEvent;
import com.city.model.AgentUiEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

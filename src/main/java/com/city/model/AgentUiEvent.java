package com.city.model;

import java.time.Instant;

/** 按会话推送的轻量 Agent UI 事件。 */
public record AgentUiEvent(
        AgentUiEventType type,
        String traceId,
        String sessionId,
        int sequence,
        String phase,
        String eventName,
        Object payload,
        Instant occurredAt
) {
}

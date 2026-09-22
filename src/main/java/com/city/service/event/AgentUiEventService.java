package com.city.service.event;

import com.city.mapper.AgentUiEventMapper;
import com.city.model.AgentUiEvent;
import com.city.model.AgentUiEventRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 以 userId + sessionId 隔离的 SSE 事件总线。
 *
 * <p>事件会先尝试写入持久化日志，再进入进程内热缓存并推送给在线订阅者。
 * 客户端重连时携带 Last-Event-ID=traceId:sequence，服务端优先从数据库补发该事件之后的事件；
 * 数据库不可用或迁移尚未执行时自动退化为内存历史，不让实时事件能力反向打断聊天主链。</p>
 *
 * <p>重放只读取事件日志，不重新执行 Agent、Tool 或业务 Workflow；SSE 断连也不等价于取消 Run。</p>
 */
@Service
public class AgentUiEventService {
    private static final Logger log = LoggerFactory.getLogger(AgentUiEventService.class);
    private static final long TIMEOUT_MS = 30L * 60L * 1000L;
    static final int MAX_HISTORY_PER_SESSION = 256;

    private final AgentUiEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final Map<SubscriptionKey, StreamState> streams = new ConcurrentHashMap<>();

    /** 纯单测入口：不依赖数据库，验证进程内 replay 语义。 */
    public AgentUiEventService() {
        this(null, new ObjectMapper().findAndRegisterModules());
    }

    @Autowired
    public AgentUiEventService(AgentUiEventMapper eventMapper, ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public SseEmitter subscribe(Long userId, String sessionId) {
        return subscribe(userId, sessionId, null);
    }

    public SseEmitter subscribe(Long userId, String sessionId, String lastEventId) {
        SubscriptionKey key = new SubscriptionKey(userId, sessionId);
        StreamState state = streams.computeIfAbsent(key, ignored -> new StreamState());
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(ignored -> remove(key, emitter));

        synchronized (state) {
            state.emitters.add(emitter);
            try {
                emitter.send(SseEmitter.event()
                        .name("connected")
                        .data(Map.of(
                                "sessionId", sessionId,
                                "replay", lastEventId != null && !lastEventId.isBlank()
                        )));
                for (AgentUiEvent event : replayEvents(userId, sessionId, state, lastEventId)) {
                    sendEvent(emitter, event);
                }
            } catch (IOException | IllegalStateException error) {
                state.emitters.remove(emitter);
                emitter.completeWithError(error);
            }
        }
        return emitter;
    }

    public void publish(Long userId, String sessionId, AgentUiEvent event) {
        if (userId == null || sessionId == null || event == null) return;

        persistEvent(userId, sessionId, event);

        SubscriptionKey key = new SubscriptionKey(userId, sessionId);
        StreamState state = streams.computeIfAbsent(key, ignored -> new StreamState());
        synchronized (state) {
            state.history.addLast(event);
            while (state.history.size() > MAX_HISTORY_PER_SESSION) {
                state.history.removeFirst();
            }

            for (SseEmitter emitter : state.emitters) {
                try {
                    sendEvent(emitter, event);
                } catch (IOException | IllegalStateException error) {
                    state.emitters.remove(emitter);
                    log.debug("Removed closed SSE subscriber: sessionId={}", sessionId);
                }
            }
        }
    }

    /** package-private：供恢复逻辑单测，不暴露为业务 API。 */
    List<AgentUiEvent> replayAfter(Long userId, String sessionId, String lastEventId) {
        StreamState state = streams.computeIfAbsent(new SubscriptionKey(userId, sessionId), ignored -> new StreamState());
        synchronized (state) {
            return replayEvents(userId, sessionId, state, lastEventId);
        }
    }

    private List<AgentUiEvent> replayEvents(Long userId,
                                            String sessionId,
                                            StreamState state,
                                            String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return List.of();
        }
        List<AgentUiEvent> durable = durableReplayAfter(userId, sessionId, lastEventId);
        if (durable != null) {
            return durable;
        }
        return eventsAfter(state, lastEventId);
    }

    /**
     * 返回 null 表示持久化日志不可用，应降级到内存；返回空列表表示数据库确认游标之后没有事件。
     */
    private List<AgentUiEvent> durableReplayAfter(Long userId, String sessionId, String lastEventId) {
        if (eventMapper == null) return null;
        try {
            EventCursor cursor = EventCursor.parse(lastEventId);
            List<AgentUiEventRow> rows;
            if (cursor != null) {
                Long cursorId = eventMapper.findCursorId(
                        userId, sessionId, cursor.traceId(), cursor.sequence());
                if (cursorId != null) {
                    rows = eventMapper.findAfterId(userId, sessionId, cursorId, MAX_HISTORY_PER_SESSION);
                } else {
                    rows = eventMapper.findRecent(userId, sessionId, MAX_HISTORY_PER_SESSION);
                    rows = rows == null ? List.of() : new ArrayList<>(rows);
                    Collections.reverse(rows);
                }
            } else {
                rows = eventMapper.findRecent(userId, sessionId, MAX_HISTORY_PER_SESSION);
                rows = rows == null ? List.of() : new ArrayList<>(rows);
                Collections.reverse(rows);
            }
            if (rows == null || rows.isEmpty()) return List.of();
            return rows.stream()
                    .map(this::toEvent)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException error) {
            log.debug("Durable SSE replay unavailable, fallback to in-memory history: sessionId={}",
                    sessionId, error);
            return null;
        }
    }

    private List<AgentUiEvent> eventsAfter(StreamState state, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank() || state.history.isEmpty()) {
            return List.of();
        }
        List<AgentUiEvent> snapshot = new ArrayList<>(state.history);
        EventCursor cursor = EventCursor.parse(lastEventId);
        if (cursor == null) {
            return List.copyOf(snapshot);
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            AgentUiEvent event = snapshot.get(i);
            if (cursor.matches(event)) {
                return i + 1 >= snapshot.size()
                        ? List.of()
                        : List.copyOf(snapshot.subList(i + 1, snapshot.size()));
            }
        }
        return List.copyOf(snapshot);
    }

    private void persistEvent(Long userId, String sessionId, AgentUiEvent event) {
        if (eventMapper == null) return;
        try {
            AgentUiEventRow row = new AgentUiEventRow();
            row.setUserId(userId);
            row.setSessionId(sessionId);
            row.setTraceId(event.traceId());
            row.setEventSeq(event.sequence());
            row.setEventJson(objectMapper.writeValueAsString(event));
            Instant occurredAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
            row.setOccurredAt(LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
            eventMapper.insertIgnore(row);
        } catch (Exception error) {
            log.debug("Failed to persist SSE event, continuing with in-memory delivery: sessionId={}, traceId={}, seq={}",
                    sessionId, event.traceId(), event.sequence(), error);
        }
    }

    private AgentUiEvent toEvent(AgentUiEventRow row) {
        if (row == null || row.getEventJson() == null || row.getEventJson().isBlank()) return null;
        try {
            return objectMapper.readValue(row.getEventJson(), AgentUiEvent.class);
        } catch (Exception error) {
            log.debug("Skip malformed persisted SSE event: id={}", row.getId(), error);
            return null;
        }
    }

    private void sendEvent(SseEmitter emitter, AgentUiEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(eventId(event))
                .name(event.type().name())
                .data(event));
    }

    private String eventId(AgentUiEvent event) {
        return event.traceId() + ':' + event.sequence();
    }

    private void remove(SubscriptionKey key, SseEmitter emitter) {
        StreamState state = streams.get(key);
        if (state == null) return;
        synchronized (state) {
            state.emitters.remove(emitter);
            // 不删除 history：断连本身不等于取消 Run，重连仍需要补发事件。
        }
    }

    private record SubscriptionKey(Long userId, String sessionId) { }

    private static final class StreamState {
        private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private final Deque<AgentUiEvent> history = new ArrayDeque<>();
    }

    private record EventCursor(String traceId, int sequence) {
        private static EventCursor parse(String value) {
            if (value == null) return null;
            String trimmed = value.trim();
            int split = trimmed.lastIndexOf(':');
            if (split <= 0 || split >= trimmed.length() - 1) return null;
            try {
                return new EventCursor(
                        trimmed.substring(0, split),
                        Integer.parseInt(trimmed.substring(split + 1))
                );
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private boolean matches(AgentUiEvent event) {
            return event != null
                    && traceId.equals(event.traceId())
                    && sequence == event.sequence();
        }
    }
}

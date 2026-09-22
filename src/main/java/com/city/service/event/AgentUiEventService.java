package com.city.service.event;

import com.city.model.AgentUiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 以 userId + sessionId 隔离的 SSE 事件总线。
 *
 * <p>每个会话保存一段有界事件历史。客户端重连时携带 Last-Event-ID=traceId:sequence，
 * 服务端只补发该事件之后的已记录事件，再继续接收 live event。重放只读取事件日志，
 * 不会重新执行 Agent、Tool 或业务 Workflow。</p>
 */
@Service
public class AgentUiEventService {
    private static final Logger log = LoggerFactory.getLogger(AgentUiEventService.class);
    private static final long TIMEOUT_MS = 30L * 60L * 1000L;
    static final int MAX_HISTORY_PER_SESSION = 256;

    private final Map<SubscriptionKey, StreamState> streams = new ConcurrentHashMap<>();

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
                for (AgentUiEvent event : eventsAfter(state, lastEventId)) {
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
        StreamState state = streams.get(new SubscriptionKey(userId, sessionId));
        if (state == null) return List.of();
        synchronized (state) {
            return eventsAfter(state, lastEventId);
        }
    }

    private List<AgentUiEvent> eventsAfter(StreamState state, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank() || state.history.isEmpty()) {
            return List.of();
        }
        List<AgentUiEvent> snapshot = new ArrayList<>(state.history);
        EventCursor cursor = EventCursor.parse(lastEventId);
        if (cursor == null) {
            // 客户端游标不可解析时补发当前保留窗口，客户端仍可按 SSE id 去重。
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
        // 游标已超出内存保留窗口时，从当前最早可用事件开始补发；不会触发业务重执行。
        return List.copyOf(snapshot);
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
            // 不在断连时删除 history：断连本身不等于取消 Run，重连仍需要补发事件。
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

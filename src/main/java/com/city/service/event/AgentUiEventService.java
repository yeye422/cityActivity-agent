package com.city.service.event;

import com.city.model.AgentUiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 以 userId + sessionId 隔离订阅者的 SSE 事件总线。 */
@Service
public class AgentUiEventService {
    private static final Logger log = LoggerFactory.getLogger(AgentUiEventService.class);
    private static final long TIMEOUT_MS = 30L * 60L * 1000L;
    private final Map<SubscriptionKey, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId, String sessionId) {
        SubscriptionKey key = new SubscriptionKey(userId, sessionId);
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(ignored -> remove(key, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("sessionId", sessionId)));
        } catch (IOException error) {
            remove(key, emitter);
            emitter.completeWithError(error);
        }
        return emitter;
    }

    public void publish(Long userId, String sessionId, AgentUiEvent event) {
        if (userId == null || sessionId == null || event == null) return;
        SubscriptionKey key = new SubscriptionKey(userId, sessionId);
        List<SseEmitter> subscribers = emitters.getOrDefault(key, new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event()
                        .id(event.traceId() + ':' + event.sequence())
                        .name(event.type().name())
                        .data(event));
            } catch (IOException | IllegalStateException error) {
                remove(key, emitter);
                log.debug("Removed closed SSE subscriber: sessionId={}", sessionId);
            }
        }
    }

    private void remove(SubscriptionKey key, SseEmitter emitter) {
        List<SseEmitter> subscribers = emitters.get(key);
        if (subscribers == null) return;
        subscribers.remove(emitter);
        if (subscribers.isEmpty()) emitters.remove(key, subscribers);
    }

    private record SubscriptionKey(Long userId, String sessionId) { }
}

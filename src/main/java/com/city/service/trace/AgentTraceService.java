package com.city.service.trace;

import com.city.exception.CityException;
import com.city.mapper.AgentTraceMapper;
import com.city.model.RequestTraceRow;
import com.city.model.TraceLabelRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 链路追踪服务。
 * 通过 ThreadLocal 收集一轮请求内的状态机事件和 Agent 调用，TraceScope close 时统一落库。
 */
@Service
public class AgentTraceService {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceService.class);
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    private static final int MAX_PAYLOAD_LENGTH = 20000;

    private final ThreadLocal<TraceScope> currentScope = new ThreadLocal<>();
    private final AgentTraceMapper agentTraceMapper;
    private final ObjectMapper objectMapper;
    private final String promptVersion;
    private final String ruleVersion;
    private final String gitCommit;

    public AgentTraceService(
            AgentTraceMapper agentTraceMapper,
            ObjectMapper objectMapper,
            BuildVersionService buildVersionService,
            @Value("${diet.prompt.version:v2}") String promptVersion,
            @Value("${diet.rule.version:v2}") String ruleVersion
    ) {
        this.agentTraceMapper = agentTraceMapper;
        this.objectMapper = objectMapper;
        this.promptVersion = promptVersion;
        this.ruleVersion = ruleVersion;
        this.gitCommit = buildVersionService.gitCommit();
    }

    public TraceScope openTrace(String traceId, String sessionId, Long userId) {
        TraceScope scope = new TraceScope(traceId, sessionId, userId);
        currentScope.set(scope);
        return scope;
    }

    public void recordEvent(String eventType, String phase, Object inputPayload, Object outputPayload) {
        record(eventType, phase, null, null, inputPayload, outputPayload,
                null, null, null, null, null);
    }

    public void recordEvent(String eventType, String phase, Object inputPayload, Object outputPayload, Long latencyMs) {
        record(eventType, phase, null, null, inputPayload, outputPayload,
                latencyMs, null, null, null, null);
    }

    public void recordError(String eventType, String phase, Object inputPayload, Exception error) {
        record(eventType, phase, null, null, inputPayload, null,
                null, null, null, null, error);
    }

    /** 同步调用 Agent，并记录模型名、Token、耗时与异常。 */
    public Msg callAgent(String sessionId, String agentName, String modelName, ReActAgent agent, String inputText) {
        long startedAt = System.nanoTime();
        try {
            Msg response = agent.call(Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(inputText)
                    .build()).block();
            recordAgentCall(sessionId, agentName, modelName, inputText,
                    response, elapsedMs(startedAt), null);
            return response;
        } catch (RuntimeException error) {
            recordAgentCall(sessionId, agentName, modelName, inputText,
                    null, elapsedMs(startedAt), error);
            throw error;
        }
    }

    public RequestTraceRow findByTraceId(Long userId, String traceId) {
        return agentTraceMapper.findByTraceId(userId, traceId);
    }

    public List<RequestTraceRow> findByTraceIds(Long userId, List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return List.of();
        return agentTraceMapper.findByTraceIds(userId, traceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList());
    }

    public List<RequestTraceRow> findBySessionId(Long userId, String sessionId, Integer limit) {
        int safeLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return agentTraceMapper.findBySessionId(userId, sessionId, safeLimit);
    }

    public List<RequestTraceRow> findByTimeRange(
            Long userId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            Boolean onlyUnlabeled,
            Integer limit
    ) {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new CityException("Trace 查询时间范围不合法");
        }
        int safeLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return agentTraceMapper.findByTimeRange(
                userId, startAt, endAt, Boolean.TRUE.equals(onlyUnlabeled), safeLimit);
    }

    public void updateLabel(Long userId, String traceId, TraceLabelRequest request) {
        if (traceId == null || traceId.isBlank()) {
            throw new CityException("TraceId 不能为空");
        }
        if (request == null) {
            throw new CityException("标注内容不能为空");
        }
        String expectedIntent = request.expectedIntent() == null
                ? null : request.expectedIntent().name();
        String expectedSlots = request.expectedSlots() == null
                ? null : toTraceJson(request.expectedSlots());
        String expectedClarifyAction = request.expectedClarifyAction();
        int updated = agentTraceMapper.updateLabel(
                userId,
                traceId,
                expectedIntent,
                expectedSlots,
                expectedClarifyAction,
                userId,
                request.labelNote()
        );
        if (updated == 0) {
            throw new CityException("Trace 不存在或无权限标注");
        }
    }

    private void recordAgentCall(
            String sessionId,
            String agentName,
            String modelName,
            String inputText,
            Msg response,
            long latencyMs,
            Exception error
    ) {
        Object output = response == null ? null : response.getTextContent();
        Long inputTokens = inputTokens(response);
        Long outputTokens = outputTokens(response);
        Long totalTokens = totalTokens(inputTokens, outputTokens);
        record("AGENT_CALL", "AGENT", agentName, modelName, inputText, output,
                latencyMs, inputTokens, outputTokens, totalTokens, error);
    }

    private void record(
            String eventType,
            String phase,
            String agentName,
            String modelName,
            Object inputPayload,
            Object outputPayload,
            Long latencyMs,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            Exception error
    ) {
        TraceScope scope = currentScope.get();
        if (scope == null) {
            return;
        }
        String errorMessage = error == null
                ? null
                : trim(error.getClass().getSimpleName() + ": " + error.getMessage());
        scope.addEvent(new TraceEvent(
                scope.nextStep(),
                eventType,
                phase,
                agentName,
                modelName,
                toPayload(inputPayload),
                toPayload(outputPayload),
                latencyMs,
                inputTokens,
                outputTokens,
                totalTokens,
                errorMessage,
                LocalDateTime.now().toString()
        ));
        if (errorMessage != null) {
            scope.markFailed(errorMessage);
        }
    }

    private void flushTrace(TraceScope scope) {
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId(scope.traceId());
        row.setSessionId(scope.sessionId());
        row.setUserId(scope.userId());
        row.setStatus(scope.status());
        row.setEventCount(scope.eventCount());
        row.setDurationMs(elapsedMs(scope.startedAt()));
        row.setErrorMessage(scope.errorMessage());

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceId", scope.traceId());
        trace.put("sessionId", scope.sessionId());
        trace.put("userId", scope.userId());
        trace.put("status", scope.status());
        trace.put("promptVersion", promptVersion);
        trace.put("ruleVersion", ruleVersion);
        trace.put("gitCommit", gitCommit);
        trace.put("durationMs", row.getDurationMs());
        trace.put("events", scope.events());

        row.setTraceJson(toTraceJson(trace));
        agentTraceMapper.insert(row);
    }

    private String toTraceJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{\"events\":[]}";
        }
    }

    private String toPayload(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String text) {
            return trim(text);
        }
        try {
            return trim(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            return trim(String.valueOf(payload));
        }
    }

    private String trim(String text) {
        if (text == null || text.length() <= MAX_PAYLOAD_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_PAYLOAD_LENGTH) + "...[truncated]";
    }

    private Long inputTokens(Msg response) {
        if (response == null || response.getChatUsage() == null) {
            return null;
        }
        Number tokens = response.getChatUsage().getInputTokens();
        return tokens == null ? null : tokens.longValue();
    }

    private Long outputTokens(Msg response) {
        if (response == null || response.getChatUsage() == null) {
            return null;
        }
        Number tokens = response.getChatUsage().getOutputTokens();
        return tokens == null ? null : tokens.longValue();
    }

    private Long totalTokens(Long inputTokens, Long outputTokens) {
        if (inputTokens == null || outputTokens == null) {
            return null;
        }
        return inputTokens + outputTokens;
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record TraceEvent(
            int stepOrder,
            String eventType,
            String phase,
            String agentName,
            String modelName,
            String inputPayload,
            String outputPayload,
            Long latencyMs,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            String errorMessage,
            String createdAt
    ) {
    }

    public final class TraceScope implements AutoCloseable {
        private final String traceId;
        private final String sessionId;
        private final Long userId;
        private final AtomicInteger stepOrder = new AtomicInteger(0);
        private final long startedAt = System.nanoTime();
        private final List<TraceEvent> events = new ArrayList<>();
        private String status = "SUCCESS";
        private String errorMessage;
        private boolean closed;

        private TraceScope(String traceId, String sessionId, Long userId) {
            this.traceId = traceId;
            this.sessionId = sessionId;
            this.userId = userId;
        }

        private String traceId() {
            return traceId;
        }

        private String sessionId() {
            return sessionId;
        }

        private Long userId() {
            return userId;
        }

        private int nextStep() {
            return stepOrder.incrementAndGet();
        }

        private long startedAt() {
            return startedAt;
        }

        private List<TraceEvent> events() {
            return List.copyOf(events);
        }

        private int eventCount() {
            return events.size();
        }

        private String status() {
            return status;
        }

        private String errorMessage() {
            return errorMessage;
        }

        private void addEvent(TraceEvent event) {
            events.add(event);
        }

        private void markFailed(String errorMessage) {
            this.status = "FAILED";
            this.errorMessage = errorMessage;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                flushTrace(this);
            } catch (RuntimeException error) {
                log.warn("Failed to persist request trace: traceId={}", traceId, error);
            } finally {
                currentScope.remove();
            }
        }
    }
}

package com.city.service.trace;

import com.city.exception.CityException;
import com.city.mapper.AgentTraceMapper;
import com.city.model.RequestTraceRow;
import com.city.model.TraceLabelRequest;
import com.city.model.AgentUiEvent;
import com.city.model.AgentUiEventType;
import com.city.service.event.AgentUiEventService;
import com.city.service.harness.AgentExecutionHarness;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轮请求级 Agent 全链路追踪服务。
 *
 * <p>Orchestrator 在请求开始时通过 openTrace 建立 TraceScope，随后意图识别、槽位变化、
 * 澄清决策、搜索、排序、Agent 调用、异常等事件都追加到当前 Scope；请求结束时 close()
 * 一次性把整条事件序列持久化到 request trace 表。</p>
 *
 * <p>ThreadLocal 只负责把“当前线程正在写哪条 Trace”传递给各业务服务，真正需要长期保存的
 * Trace 数据最终仍落数据库。TraceScope 必须通过 try-with-resources 关闭，防止线程池复用时串 Trace。</p>
 */
@Service
public class AgentTraceService {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceService.class);

    /** Trace 查询默认返回条数。 */
    private static final int DEFAULT_LIMIT = 200;

    /** 防止后台查询一次拉取过多 Trace。 */
    private static final int MAX_LIMIT = 1000;

    /** 单个 input/output/error payload 最大保存长度，避免 Trace JSON 被大 Prompt 或响应无限放大。 */
    private static final int MAX_PAYLOAD_LENGTH = 20000;

    /** 当前请求线程绑定的 TraceScope；Scope.close() 时必须 remove。 */
    private final ThreadLocal<TraceScope> currentScope = new ThreadLocal<>();

    /** AgentScope Tool/Hook 会切换到 Reactor worker 线程；显式 traceId 用于跨线程续写当前请求。 */
    private final Map<String, TraceScope> activeScopes = new ConcurrentHashMap<>();

    private final AgentTraceMapper agentTraceMapper;
    private final ObjectMapper objectMapper;
    private final String promptVersion;
    private final String ruleVersion;
    private final String gitCommit;
    private final AgentExecutionHarness executionHarness = new AgentExecutionHarness();
    private final AgentUiEventService agentUiEventService;

    @Autowired
    public AgentTraceService(
            AgentTraceMapper agentTraceMapper,
            ObjectMapper objectMapper,
            BuildVersionService buildVersionService,
            @Value("${city.prompt.version:v2}") String promptVersion,
            @Value("${city.rule.version:v2}") String ruleVersion,
            AgentUiEventService agentUiEventService
    ) {
        this.agentTraceMapper = agentTraceMapper;
        this.objectMapper = objectMapper;
        this.promptVersion = promptVersion;
        this.ruleVersion = ruleVersion;
        this.gitCommit = buildVersionService.gitCommit();
        this.agentUiEventService = agentUiEventService;
    }

    /** 保留不启动 Web 层的单元测试构造入口。 */
    public AgentTraceService(
            AgentTraceMapper agentTraceMapper,
            ObjectMapper objectMapper,
            BuildVersionService buildVersionService,
            String promptVersion,
            String ruleVersion
    ) {
        this(agentTraceMapper, objectMapper, buildVersionService, promptVersion, ruleVersion, null);
    }

    /**
     * 为一轮请求创建 Trace 上下文并绑定到当前线程。
     * 调用方应使用 try-with-resources，确保成功和异常路径最终都会 flush 并清理 ThreadLocal。
     */
    public TraceScope openTrace(String traceId, String sessionId, Long userId) {
        TraceScope scope = new TraceScope(
                traceId, sessionId, userId, executionHarness.openRun(sessionId));
        currentScope.set(scope);
        activeScopes.put(traceId, scope);
        return scope;
    }

    /** 记录一个不带耗时的普通状态机事件。 */
    public void recordEvent(String eventType, String phase, Object inputPayload, Object outputPayload) {
        record(eventType, phase, null, null, inputPayload, outputPayload,
                null, null, null, null, null);
    }

    /** 记录一个带阶段耗时的普通状态机事件。 */
    public void recordEvent(String eventType, String phase, Object inputPayload, Object outputPayload, Long latencyMs) {
        record(eventType, phase, null, null, inputPayload, outputPayload,
                latencyMs, null, null, null, null);
    }

    /**
     * AgentScope Tool/Hook 在 Reactor worker 线程执行时，按 traceId 显式记录事件。
     * 这条路径不依赖 ThreadLocal，因此工具调用不会在切线程后丢失。
     */
    public void recordEventForTrace(String traceId,
                                    String eventType,
                                    String phase,
                                    Object inputPayload,
                                    Object outputPayload) {
        recordForTrace(traceId, eventType, phase, null, null, inputPayload, outputPayload,
                null, null, null, null, null);
    }

    /** 在异步 AgentScope 线程中按 traceId 记录异常。 */
    public void recordErrorForTrace(String traceId,
                                    String eventType,
                                    String phase,
                                    Object inputPayload,
                                    Exception error) {
        recordForTrace(traceId, eventType, phase, null, null, inputPayload, null,
                null, null, null, null, error);
    }

    /** 记录业务阶段异常，并把当前 Trace 标记为 FAILED。 */
    public void recordError(String eventType, String phase, Object inputPayload, Exception error) {
        record(eventType, phase, null, null, inputPayload, null,
                null, null, null, null, error);
    }

    /**
     * 同步调用一个 Agent，并自动采集模型名、输入输出、Token、耗时和异常。
     *
     * <p>Agent 调用统一从这里经过，能够避免各 Worker 自己实现一套不一致的可观测逻辑。</p>
     */
    public Msg callAgent(String sessionId, String agentName, String modelName, ReActAgent agent, String inputText) {
        long startedAt = System.nanoTime();
        AgentExecutionHarness.CallPermit permit;
        try {
            permit = executionHarness.beforeCall(agentName, inputText);
        } catch (RuntimeException error) {
            recordAgentCall(sessionId, agentName, modelName, inputText,
                    null, elapsedMs(startedAt), error);
            throw error;
        }
        try {
            Msg response = agent.call(List.of(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(inputText)
                            .build()
            )).block();
            executionHarness.recordSuccess(permit);
            recordAgentCall(sessionId, agentName, modelName, inputText,
                    response, elapsedMs(startedAt), null);
            return response;
        } catch (RuntimeException error) {
            executionHarness.recordFailure(permit);
            // 异常同样进入 Trace，然后保持原异常语义继续向上抛出。
            recordAgentCall(sessionId, agentName, modelName, inputText,
                    null, elapsedMs(startedAt), error);
            throw error;
        }
    }

    /** 按 traceId 查询单条完整请求 Trace。 */
    public RequestTraceRow findByTraceId(Long userId, String traceId) {
        return agentTraceMapper.findByTraceId(userId, traceId);
    }

    /** 批量查询 Trace，并在进入 Mapper 前过滤空 ID 和重复 ID。 */
    public List<RequestTraceRow> findByTraceIds(Long userId, List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) return List.of();
        return agentTraceMapper.findByTraceIds(userId, traceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList());
    }

    /** 查询某个 Session 下最近的 Trace，limit 被限制在 1~1000。 */
    public List<RequestTraceRow> findBySessionId(Long userId, String sessionId, Integer limit) {
        int safeLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return agentTraceMapper.findBySessionId(userId, sessionId, safeLimit);
    }

    /**
     * 按时间窗口读取 Trace，可选只查看尚未人工标注的样本。
     * 时间区间采用 [startAt, endAt) 的语义，由 Mapper 查询实现。
     */
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

    /**
     * 写入人工标准答案/标注信息，供离线评估与回归检测使用。
     * expectedSlots 在数据库中保存为 JSON，Intent 则保存枚举名称。
     */
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

    /**
     * 把 AgentScope 的 Msg 统一转换成 AGENT_CALL 事件。
     * Token 从 response.chatUsage 读取；供应商未返回 usage 时保持 null，而不是伪造 0。
     */
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

    /**
     * 所有 Trace 事件最终汇聚到这里。
     * 没有 active TraceScope 时直接返回，因此普通单元测试或非请求线程调用不会因为 Trace 机制失败。
     */
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
        record(scope, eventType, phase, agentName, modelName, inputPayload, outputPayload,
                latencyMs, inputTokens, outputTokens, totalTokens, error);
    }

    private void recordForTrace(
            String traceId,
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
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        TraceScope scope = activeScopes.get(traceId.trim());
        if (scope == null) {
            return;
        }
        record(scope, eventType, phase, agentName, modelName, inputPayload, outputPayload,
                latencyMs, inputTokens, outputTokens, totalTokens, error);
    }

    private void record(
            TraceScope scope,
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
        String errorMessage = error == null
                ? null
                : trim(error.getClass().getSimpleName() + ": " + error.getMessage());

        int sequence = scope.nextStep();
        scope.addEvent(new TraceEvent(
                sequence,
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
        publishUiEvent(scope, sequence, eventType, phase, outputPayload, errorMessage);
    }

    private void publishUiEvent(TraceScope scope,
                                int sequence,
                                String eventType,
                                String phase,
                                Object outputPayload,
                                String errorMessage) {
        if (agentUiEventService == null) return;
        AgentUiEventType uiType = toUiEventType(eventType, errorMessage);
        Object payload = errorMessage == null ? outputPayload : Map.of("message", errorMessage);
        agentUiEventService.publish(scope.userId(), scope.sessionId(), new AgentUiEvent(
                uiType, scope.traceId(), scope.sessionId(), sequence, phase,
                eventType, payload, Instant.now()));
    }

    static AgentUiEventType toUiEventType(String eventType, String errorMessage) {
        if (errorMessage != null || isTerminalUiFailure(eventType)) {
            return AgentUiEventType.ERROR;
        }
        if ("REQUEST_RECEIVED".equals(eventType)) return AgentUiEventType.RUN_STARTED;
        if ("REQUEST_FINISHED".equals(eventType)) return AgentUiEventType.RUN_FINISHED;
        if ("RESPONSE_READY".equals(eventType) || "RESPONSE_AGENT_RESULT".equals(eventType)) {
            return AgentUiEventType.MESSAGE_COMPLETE;
        }
        if ("WORKER_DISPATCHED".equals(eventType) || "AGENT_CALL".equals(eventType)) {
            return AgentUiEventType.STEP_STARTED;
        }
        return AgentUiEventType.STEP_COMPLETED;
    }

    private static boolean isTerminalUiFailure(String eventType) {
        return "REQUEST_FAILED".equals(eventType)
                || "RECOMMENDATION_AGENT_FAILED".equals(eventType)
                || "PLANNING_AGENT_FAILED".equals(eventType)
                || "RECOMMENDATION_REACT_FAILED".equals(eventType)
                || "PLANNING_REACT_FAILED".equals(eventType);
    }

    /**
     * 将内存中的整条 Trace 聚合成 RequestTraceRow 并一次性落库。
     * Trace 顶层额外记录 promptVersion、ruleVersion、gitCommit，用于评估时精确定位运行版本。
     */
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

    /** Trace 顶层 JSON 序列化失败时返回最小合法结构，避免追踪系统反向打断业务请求。 */
    private String toTraceJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "{\"events\":[]}";
        }
    }

    /**
     * 将任意事件 payload 归一化为可保存字符串，并统一执行长度截断。
     * String 原样保存；结构化对象优先 JSON；JSON 失败时再退化到 toString()。
     */
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

    /** 单个 payload 最多保存 20000 字符，超出部分明确标记 truncated。 */
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

    /** 只有输入和输出 Token 都可用时才计算 total，避免把未知值误当成 0。 */
    private Long totalTokens(Long inputTokens, Long outputTokens) {
        if (inputTokens == null || outputTokens == null) {
            return null;
        }
        return inputTokens + outputTokens;
    }

    /** 将 System.nanoTime 的差值转换为毫秒，只用于耗时统计，不参与业务时间。 */
    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /** Trace 中的最小事件单元，stepOrder 决定回放顺序。 */
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

    /**
     * 一轮请求的可关闭 Trace 上下文。
     *
     * <p>Scope 内部累计事件和状态；close 时只允许 flush 一次，并无论落库成功与否都清理 ThreadLocal。
     * Trace 持久化失败只记 WARN，不反向影响用户主请求。</p>
     */
    public final class TraceScope implements AutoCloseable {
        private final String traceId;
        private final String sessionId;
        private final Long userId;
        private final AtomicInteger stepOrder = new AtomicInteger(0);
        private final long startedAt = System.nanoTime();
        private final List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        private String status = "SUCCESS";
        private String errorMessage;
        private boolean closed;
        private final AgentExecutionHarness.RunScope harnessScope;

        private TraceScope(String traceId, String sessionId, Long userId,
                           AgentExecutionHarness.RunScope harnessScope) {
            this.traceId = traceId;
            this.sessionId = sessionId;
            this.userId = userId;
            this.harnessScope = harnessScope;
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
            synchronized (events) {
                return List.copyOf(events);
            }
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

        /** 任一事件出现异常后，整条请求 Trace 标记为 FAILED，并保留最近一次错误信息。 */
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
                // 可观测链路是旁路能力：数据库 Trace 写入失败不能覆盖真实业务结果。
                log.warn("Failed to persist request trace: traceId={}", traceId, error);
            } finally {
                activeScopes.remove(traceId, this);
                harnessScope.close();
                // 线程池会复用线程；不 remove 会导致下一次请求继续写入旧 Scope。
                if (currentScope.get() == this) {
                    currentScope.remove();
                }
            }
        }
    }
}

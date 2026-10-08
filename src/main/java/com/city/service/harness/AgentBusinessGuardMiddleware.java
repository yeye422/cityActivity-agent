package com.city.service.harness;

import com.city.enums.DegradationReason;
import com.city.exception.CityException;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * AgentScope 2.x ReAct 的业务治理 Middleware。
 *
 * <p>只负责 CityFlow 的业务边界：Tool 白名单、Acting 总预算、重复 Tool Signature
 * 检测和业务 Trace。每次 Agent build 创建独立实例，因此计数天然限定在单次 Agent Run。</p>
 */
public final class AgentBusinessGuardMiddleware implements MiddlewareBase {

    /** AgentScope structured-output fallback 的框架级 Tool，不计入 CityFlow 业务 Tool 预算。 */
    static final String FRAMEWORK_RESPONSE_TOOL = "generate_response";

    private final String agentName;
    private final Set<String> allowedTools;
    private final int maxActingCalls;
    private final int repeatThreshold;
    private final AgentTraceService traceService;
    private final String traceId;
    private final Map<String, Integer> signatures = new HashMap<>();
    private int reasoningSteps;
    private int actingCalls;

    public AgentBusinessGuardMiddleware(String agentName,
                                        Set<String> allowedTools,
                                        int maxActingCalls,
                                        int repeatThreshold,
                                        AgentTraceService traceService) {
        this(agentName, allowedTools, maxActingCalls, repeatThreshold, traceService, null);
    }

    public AgentBusinessGuardMiddleware(String agentName,
                                        Set<String> allowedTools,
                                        int maxActingCalls,
                                        int repeatThreshold,
                                        AgentTraceService traceService,
                                        String traceId) {
        this.agentName = requireText(agentName, "agentName");
        this.allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        if (maxActingCalls <= 0 || repeatThreshold <= 1) {
            throw new IllegalArgumentException("Agent Guard 预算参数不合法");
        }
        this.maxActingCalls = maxActingCalls;
        this.repeatThreshold = repeatThreshold;
        this.traceService = traceService;
        this.traceId = traceId == null ? "" : traceId.trim();
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent,
                                        RuntimeContext context,
                                        ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        synchronized (this) {
            reasoningSteps++;
            record("AGENT_REASONING_STARTED", Map.of("reasoningStep", reasoningSteps));
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent,
                                     RuntimeContext context,
                                     ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> toolCalls = input == null || input.toolCalls() == null
                ? List.of()
                : List.copyOf(input.toolCalls());
        try {
            for (ToolUseBlock toolCall : toolCalls) {
                String toolName = toolCall == null ? "" : toolCall.getName();
                Object toolInput = toolCall == null ? null : toolCall.getInput();
                beforeToolCall(toolName, toolInput);
                record("AGENT_TOOL_GUARD_PASSED", Map.of(
                        "toolName", toolName,
                        "actingCall", actingCalls()
                ));
            }
        } catch (RuntimeException error) {
            String blockedTool = toolCalls.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ToolUseBlock::getName)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse("");
            recordError("AGENT_TOOL_GUARD_BLOCKED", blockedTool, error);
            return Flux.error(error);
        }

        return next.apply(input)
                .doOnComplete(() -> {
                    for (ToolUseBlock toolCall : toolCalls) {
                        if (toolCall == null) continue;
                        record("AGENT_TOOL_GUARD_COMPLETED", Map.of(
                                "toolName", toolCall.getName(),
                                "actingCall", actingCalls()
                        ));
                    }
                });
    }

    /** package-private：纯单测可直接验证治理规则，不需要构造 AgentScope event。 */
    synchronized void beforeToolCall(String toolName, Object input) {
        String safeTool = requireText(toolName, "toolName");

        if (FRAMEWORK_RESPONSE_TOOL.equals(safeTool)) {
            return;
        }
        if (!allowedTools.contains(safeTool)) {
            throw new CityException("Agent 无权调用工具: " + safeTool);
        }

        actingCalls++;
        if (actingCalls > maxActingCalls) {
            throw new AgentExecutionHarness.AgentHarnessException(
                    "Agent Tool 调用已达到预算上限: " + maxActingCalls,
                    DegradationReason.CALL_BUDGET_EXCEEDED
            );
        }

        String signature = safeTool + ':' + String.valueOf(input);
        int repeated = signatures.merge(signature, 1, Integer::sum);
        if (repeated >= repeatThreshold) {
            throw new AgentExecutionHarness.AgentHarnessException(
                    "检测到重复 Tool 调用，已停止 ReAct 循环: " + safeTool,
                    DegradationReason.LOOP_DETECTED
            );
        }
    }

    synchronized int actingCalls() {
        return actingCalls;
    }

    synchronized int reasoningSteps() {
        return reasoningSteps;
    }

    @Override
    public int order() {
        return 50;
    }

    private void record(String eventType, Object output) {
        if (traceService == null) return;
        if (!traceId.isBlank()) {
            traceService.recordEventForTrace(
                    traceId, eventType, "AGENT_GUARD", Map.of("agentName", agentName), output);
            return;
        }
        traceService.recordEvent(eventType, "AGENT_GUARD", Map.of("agentName", agentName), output);
    }

    private void recordError(String eventType, String toolName, RuntimeException error) {
        if (traceService == null) return;
        Map<String, Object> input = Map.of(
                "agentName", agentName,
                "toolName", toolName == null ? "" : toolName
        );
        if (!traceId.isBlank()) {
            traceService.recordErrorForTrace(traceId, eventType, "AGENT_GUARD", input, error);
            return;
        }
        traceService.recordError(eventType, "AGENT_GUARD", input, error);
    }

    private static String requireText(String value, String field) {
        String safe = value == null ? "" : value.trim();
        if (safe.isEmpty()) throw new IllegalArgumentException(field + " 不能为空");
        return safe;
    }
}

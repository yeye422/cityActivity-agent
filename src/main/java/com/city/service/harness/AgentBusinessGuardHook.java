package com.city.service.harness;

import com.city.enums.DegradationReason;
import com.city.exception.CityException;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AgentScope ReAct 的业务治理 Hook。
 *
 * <p>AgentScope 继续负责 Reason -> Act -> Observe 循环；本 Hook 只做 CityFlow 业务边界：
 * 工具二次白名单、Acting 总预算、重复 Tool Signature 检测和业务 Trace。每次 Agent build 创建
 * 独立 Hook，因此计数天然限定在单次 Recommendation/Planning Agent Run 内。</p>
 */
public final class AgentBusinessGuardHook implements Hook {

    /** AgentScope 为强类型结构化输出自动注册的框架级 Tool，不属于 CityFlow 业务 Tool。 */
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

    public AgentBusinessGuardHook(String agentName,
                                  Set<String> allowedTools,
                                  int maxActingCalls,
                                  int repeatThreshold,
                                  AgentTraceService traceService) {
        this(agentName, allowedTools, maxActingCalls, repeatThreshold, traceService, null);
    }

    public AgentBusinessGuardHook(String agentName,
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
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent) {
            reasoningSteps++;
            record("AGENT_REASONING_STARTED", Map.of("reasoningStep", reasoningSteps));
            return Mono.just(event);
        }

        if (event instanceof PreActingEvent actingEvent) {
            String toolName = actingEvent.getToolUse() == null
                    ? ""
                    : String.valueOf(actingEvent.getToolUse().getName());
            Object toolInput = actingEvent.getToolUse() == null
                    ? null
                    : actingEvent.getToolUse().getInput();
            try {
                beforeToolCall(toolName, toolInput);
                record("AGENT_TOOL_GUARD_PASSED", Map.of(
                        "toolName", toolName,
                        "actingCall", actingCalls
                ));
                return Mono.just(event);
            } catch (RuntimeException error) {
                recordError("AGENT_TOOL_GUARD_BLOCKED", toolName, error);
                return Mono.error(error);
            }
        }

        if (event instanceof PostActingEvent actingEvent) {
            String toolName = actingEvent.getToolUse() == null
                    ? ""
                    : String.valueOf(actingEvent.getToolUse().getName());
            record("AGENT_TOOL_GUARD_COMPLETED", Map.of(
                    "toolName", toolName,
                    "actingCall", actingCalls
            ));
        }
        return Mono.just(event);
    }

    /** package-private 便于纯单测验证，不需要构造 AgentScope HookEvent。 */
    synchronized void beforeToolCall(String toolName, Object input) {
        String safeTool = requireText(toolName, "toolName");

        // generate_response 由 AgentScope 在 call(..., StructuredType.class) 时自动注入，
        // 仅用于提交最终强类型响应。它不是业务能力，不应占用业务 Tool 配额或参与重复调用检测。
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
    public int priority() {
        // 权限 / 预算类 Hook 应先于普通日志 Hook 执行。
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

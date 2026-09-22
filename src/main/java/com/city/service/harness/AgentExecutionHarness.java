package com.city.service.harness;

import com.city.exception.CityException;
import com.city.enums.DegradationReason;
import com.city.enums.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 调用边界上的 Harness：限制单轮调用量、检测重复输入并提供进程内三态熔断。
 * 业务服务捕获其异常后继续走既有 Java Parser/模板降级，避免模型故障击穿主流程。
 */
public final class AgentExecutionHarness {
    private final int maxCalls;
    private final int repeatThreshold;
    private final int failureThreshold;
    private final Duration resetAfter;
    private final ThreadLocal<RunState> currentRun = new ThreadLocal<>();
    private final Map<String, BreakerState> breakers = new ConcurrentHashMap<>();

    public AgentExecutionHarness() {
        this(8, 3, 3, Duration.ofSeconds(30));
    }

    AgentExecutionHarness(int maxCalls, int repeatThreshold,
                          int failureThreshold, Duration resetAfter) {
        this.maxCalls = maxCalls;
        this.repeatThreshold = repeatThreshold;
        this.failureThreshold = failureThreshold;
        this.resetAfter = resetAfter;
    }

    public RunScope openRun(String sessionId) {
        RunState previous = currentRun.get();
        currentRun.set(new RunState(sessionId));
        return new RunScope(previous);
    }

    public CallPermit beforeCall(String agentName, String inputText) {
        RunState run = currentRun.get();
        if (run != null) {
            run.totalCalls++;
            if (run.totalCalls > maxCalls) {
                throw new AgentHarnessException("本轮模型调用已达到预算上限",
                        DegradationReason.CALL_BUDGET_EXCEEDED);
            }
            String signature = agentName + ':' + fingerprint(inputText);
            int repeated = run.signatures.merge(signature, 1, Integer::sum);
            if (repeated >= repeatThreshold) {
                throw new AgentHarnessException("检测到重复的 Agent 调用，已停止继续消耗模型资源",
                        DegradationReason.LOOP_DETECTED);
            }
        }

        BreakerState breaker = breakers.computeIfAbsent(agentName, ignored -> new BreakerState());
        synchronized (breaker) {
            if (breaker.openedAt != null) {
                if (Duration.between(breaker.openedAt, Instant.now()).compareTo(resetAfter) < 0) {
                    throw new AgentHarnessException(agentName + " 暂时熔断，请使用降级结果",
                            DegradationReason.CIRCUIT_OPEN);
                }
                if (breaker.halfOpenProbe) {
                    throw new AgentHarnessException(agentName + " 正在进行半开探测，请使用降级结果",
                            DegradationReason.CIRCUIT_OPEN);
                }
                breaker.halfOpenProbe = true;
            }
        }
        return new CallPermit(agentName);
    }

    public void recordSuccess(CallPermit permit) {
        BreakerState breaker = breakers.computeIfAbsent(permit.agentName(), ignored -> new BreakerState());
        synchronized (breaker) {
            breaker.failures = 0;
            breaker.openedAt = null;
            breaker.halfOpenProbe = false;
        }
    }

    public void recordFailure(CallPermit permit) {
        BreakerState breaker = breakers.computeIfAbsent(permit.agentName(), ignored -> new BreakerState());
        synchronized (breaker) {
            breaker.halfOpenProbe = false;
            breaker.failures++;
            if (breaker.failures >= failureThreshold) {
                breaker.openedAt = Instant.now();
            }
        }
    }

    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    public record CallPermit(String agentName) { }

    public final class RunScope implements AutoCloseable {
        private final RunState previous;
        private boolean closed;

        private RunScope(RunState previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) currentRun.remove();
            else currentRun.set(previous);
        }
    }

    private static final class RunState {
        @SuppressWarnings("unused")
        private final String sessionId;
        private final Map<String, Integer> signatures = new HashMap<>();
        private int totalCalls;

        private RunState(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    private static final class BreakerState {
        private int failures;
        private Instant openedAt;
        private boolean halfOpenProbe;
    }

    public static final class AgentHarnessException extends CityException {
        public AgentHarnessException(String message, DegradationReason degradationReason) {
            super(ErrorCode.AGENT_BUDGET_EXCEEDED, message, degradationReason);
        }
    }
}

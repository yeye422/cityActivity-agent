package com.city.service.worker;

import com.city.model.agent.AgentTask;
import com.city.service.agent.ToolContractRegistry;
import com.city.service.trace.AgentTraceService;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Worker 的统一调度边界。
 *
 * <p>第一阶段先统一合同校验、deadline、Trace 和异常收口，并保留强类型 Worker 返回值。
 * P3 再把工具/Token/循环等完整 Harness 治理继续收敛到该边界。</p>
 */
public final class WorkerDispatcher {
    private final ToolContractRegistry toolContractRegistry;
    private final AgentTraceService agentTraceService;

    public WorkerDispatcher(ToolContractRegistry toolContractRegistry,
                            AgentTraceService agentTraceService) {
        this.toolContractRegistry = Objects.requireNonNull(toolContractRegistry, "toolContractRegistry");
        this.agentTraceService = Objects.requireNonNull(agentTraceService, "agentTraceService");
    }

    public <T> T dispatch(AgentTask task, WorkerCall<T> call) {
        Objects.requireNonNull(task, "AgentTask 不能为空");
        Objects.requireNonNull(call, "WorkerCall 不能为空");
        toolContractRegistry.validate(task);
        if (task.deadline() != null && Instant.now().isAfter(task.deadline())) {
            throw new IllegalStateException("Worker 任务已超过 deadline: " + task.taskId());
        }

        long startedAt = System.nanoTime();
        agentTraceService.recordEvent("WORKER_DISPATCHED", "AGENT", null, task);
        try {
            T result = call.execute();
            agentTraceService.recordEvent(
                    "WORKER_COMPLETED",
                    "AGENT",
                    task,
                    Map.of("taskId", task.taskId(), "taskType", task.type()),
                    elapsedMs(startedAt)
            );
            return result;
        } catch (RuntimeException error) {
            agentTraceService.recordError("WORKER_FAILED", "AGENT", task, error);
            throw error;
        }
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    @FunctionalInterface
    public interface WorkerCall<T> {
        T execute();
    }
}

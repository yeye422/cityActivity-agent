package com.city.model.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Supervisor 向 Worker 派发的最小、自包含任务合同。 */
public record AgentTask(
        String taskId,
        String sessionId,
        AgentTaskType type,
        boolean readOnly,
        Map<String, Object> contextSnapshot,
        Set<ToolCapability> allowedCapabilities,
        List<EvidenceRef> evidenceRefs,
        Instant deadline,
        String traceId,
        String runId,
        String parentTaskId,
        ExecutionBudget executionBudget
) {
    public AgentTask {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId不能为空");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId不能为空");
        if (type == null) throw new IllegalArgumentException("任务类型不能为空");
        contextSnapshot = contextSnapshot == null ? Map.of() : Map.copyOf(contextSnapshot);
        allowedCapabilities = allowedCapabilities == null ? Set.of() : Set.copyOf(allowedCapabilities);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        traceId = normalizeOptionalId(traceId);
        runId = normalizeOptionalId(runId);
        parentTaskId = normalizeOptionalId(parentTaskId);
        executionBudget = executionBudget == null ? ExecutionBudget.inheritDefaults() : executionBudget;
        if (readOnly && allowedCapabilities.stream()
                .anyMatch(capability -> capability.accessMode() == ToolAccessMode.WRITE)) {
            throw new IllegalArgumentException("只读 Worker 不能持有写能力");
        }
    }

    /** 兼容现有调用方；运行字段在 Supervisor / RunContext 收敛后逐步显式传入。 */
    public AgentTask(String taskId,
                     String sessionId,
                     AgentTaskType type,
                     boolean readOnly,
                     Map<String, Object> contextSnapshot,
                     Set<ToolCapability> allowedCapabilities,
                     List<EvidenceRef> evidenceRefs,
                     Instant deadline) {
        this(taskId, sessionId, type, readOnly, contextSnapshot, allowedCapabilities,
                evidenceRefs, deadline, null, null, null, ExecutionBudget.inheritDefaults());
    }

    private static String normalizeOptionalId(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

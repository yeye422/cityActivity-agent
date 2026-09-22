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
        Instant deadline
) {
    public AgentTask {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId不能为空");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId不能为空");
        if (type == null) throw new IllegalArgumentException("任务类型不能为空");
        contextSnapshot = contextSnapshot == null ? Map.of() : Map.copyOf(contextSnapshot);
        allowedCapabilities = allowedCapabilities == null ? Set.of() : Set.copyOf(allowedCapabilities);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        if (readOnly && allowedCapabilities.stream()
                .anyMatch(capability -> capability.accessMode() == ToolAccessMode.WRITE)) {
            throw new IllegalArgumentException("只读 Worker 不能持有写能力");
        }
    }
}

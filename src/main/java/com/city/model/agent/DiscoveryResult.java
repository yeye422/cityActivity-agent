package com.city.model.agent;

import com.city.model.ActivityItem;

import java.util.List;

/** Discovery 阶段的候选与证据必须一起返回。 */
public record DiscoveryResult(
        List<ActivityItem> candidates,
        AgentResult agentResult
) {
    public DiscoveryResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (agentResult == null) throw new IllegalArgumentException("DiscoveryResult 缺少 AgentResult");
    }
}

package com.city.model.agent;

import com.city.service.plan.ActivityPlanService;

import java.util.List;

/** Planning Worker 的窗口结果与证据合同。 */
public record PlanningResult(
        List<ActivityPlanService.PlannedActivity> plans,
        AgentResult agentResult
) {
    public PlanningResult {
        plans = plans == null ? List.of() : List.copyOf(plans);
        if (agentResult == null) throw new IllegalArgumentException("PlanningResult 缺少 AgentResult");
    }
}

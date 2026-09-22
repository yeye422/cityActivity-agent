package com.city.tool;

import com.city.model.agent.PlanningResult;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.PlanningDiscoveryToolResult;
import com.city.service.plan.ActivityPlanService;
import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * PlanningAgent 的只读候选发现 Tool。窗口、用户、数据源和所有硬约束均来自模型不可见 PlanningToolContext。
 */
@Component
public class PlanningDiscoveryTool {

    private final ActivityPlanService activityPlanService;

    public PlanningDiscoveryTool(ActivityPlanService activityPlanService) {
        this.activityPlanService = Objects.requireNonNull(activityPlanService, "activityPlanService");
    }

    @Tool(
            name = "discover_plan_candidates",
            description = "Load legal activity and session candidates for each server-defined planning window. "
                    + "The server fixes city, time, budget-related filters and exclusions."
    )
    public PlanningDiscoveryToolResult discover(PlanningToolContext planningContext) {
        Objects.requireNonNull(planningContext, "planningContext");
        planningContext.evidenceRegistry().beginDiscovery();
        VerifiedRequestContext verified = planningContext.verifiedRequestContext();
        PlanningResult result = activityPlanService.planWithEvidence(
                verified.sourceMode(),
                verified.userId(),
                verified.effectiveSlots(),
                verified.hardConstraints().excludedSlots(),
                planningContext.windows(),
                verified.hardConstraints().timeConstraint(),
                verified.weather()
        );
        planningContext.evidenceRegistry().record(result.plans());
        return PlanningDiscoveryToolResult.from(result.plans());
    }
}

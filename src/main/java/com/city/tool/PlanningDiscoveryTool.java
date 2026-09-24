package com.city.tool;

import com.city.model.agent.PlanningResult;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.PlanningDiscoveryToolResult;
import com.city.service.plan.ActivityPlanService;
import com.city.service.trace.AgentTraceService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * Planning 的只读候选发现 Java 边界。窗口、用户、数据源和所有硬约束均来自 PlanningToolContext。
 */
@Component
public class PlanningDiscoveryTool {

    private final ActivityPlanService activityPlanService;
    private final AgentTraceService traceService;

    public PlanningDiscoveryTool(ActivityPlanService activityPlanService,
                                 AgentTraceService traceService) {
        this.activityPlanService = Objects.requireNonNull(activityPlanService, "activityPlanService");
        this.traceService = traceService;
    }

    public PlanningDiscoveryToolResult discover(PlanningToolContext planningContext) {
        Objects.requireNonNull(planningContext, "planningContext");
        planningContext.evidenceRegistry().beginDiscovery();
        if (traceService != null) {
            traceService.recordEventForTrace(
                    planningContext.verifiedRequestContext().traceId(),
                    "PLANNING_DISCOVERY_TOOL_CALLED",
                    "TOOL",
                    Map.of(
                            "windows", planningContext.windows(),
                            "discoveryCall", planningContext.evidenceRegistry().discoveryCalls()
                    ),
                    null
            );
        }

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
        planningContext.notebook().recordDiscovery(planningContext.evidenceRegistry().periods());
        PlanningDiscoveryToolResult toolResult = PlanningDiscoveryToolResult.from(result.plans());
        if (traceService != null) {
            traceService.recordEventForTrace(
                    planningContext.verifiedRequestContext().traceId(),
                    "PLANNING_DISCOVERY_TOOL_COMPLETED",
                    "TOOL",
                    Map.of("windows", planningContext.windows()),
                    Map.of(
                            "windowCount", toolResult.windows().size(),
                            "exposedActivityIds", planningContext.evidenceRegistry().exposedActivityIds(),
                            "notebook", planningContext.notebook().snapshot()
                    )
            );
        }
        return toolResult;
    }
}

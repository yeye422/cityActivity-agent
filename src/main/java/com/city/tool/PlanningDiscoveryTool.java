package com.city.tool;

import com.city.model.context.PlanningToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.PlanningDiscoveryToolResult;
import com.city.service.plan.ActivityPlanService;
import com.city.service.trace.AgentTraceService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Planning 首次候选发现边界：按完整 PlanningHorizon 检索，不预切固定 period。 */
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

        VerifiedRequestContext verified = planningContext.verifiedRequestContext();
        if (traceService != null) {
            traceService.recordEventForTrace(
                    verified.traceId(),
                    "PLANNING_DISCOVERY_TOOL_CALLED",
                    "TOOL",
                    Map.of(
                            "horizon", planningContext.horizon(),
                            "discoveryCall", planningContext.evidenceRegistry().discoveryCalls()
                    ),
                    null
            );
        }

        List<ActivityPlanService.CandidateBatch> batches = activityPlanService.discoverHorizon(
                verified.sourceMode(),
                verified.userId(),
                verified.effectiveSlots(),
                verified.hardConstraints().excludedSlots(),
                planningContext.horizon(),
                verified.weather()
        );
        planningContext.evidenceRegistry().record(batches);
        planningContext.notebook().recordDiscovery(planningContext.evidenceRegistry().searchedRanges());

        PlanningDiscoveryToolResult result = PlanningDiscoveryToolResult.from(
                planningContext.evidenceRegistry().searchedRanges(),
                planningContext.evidenceRegistry().activities(),
                planningContext.evidenceRegistry().sessionsByActivityId()
        );

        if (traceService != null) {
            traceService.recordEventForTrace(
                    verified.traceId(),
                    "PLANNING_DISCOVERY_TOOL_COMPLETED",
                    "TOOL",
                    Map.of("horizon", planningContext.horizon()),
                    Map.of(
                            "searchedRanges", result.searchedRanges(),
                            "candidateCount", result.candidates().size(),
                            "exposedActivityIds", planningContext.evidenceRegistry().exposedActivityIds(),
                            "notebook", planningContext.notebook().snapshot()
                    )
            );
        }
        return result;
    }
}

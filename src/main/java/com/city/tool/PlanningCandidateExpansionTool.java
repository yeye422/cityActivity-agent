package com.city.tool;

import com.city.exception.CityException;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.PlanningDiscoveryToolResult;
import com.city.service.plan.ActivityPlanService;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** PlanningAgent 的受控单窗口候选扩展 Tool。 */
@Component
public class PlanningCandidateExpansionTool {
    private final ActivityPlanService activityPlanService;
    private final AgentTraceService traceService;

    public PlanningCandidateExpansionTool(ActivityPlanService activityPlanService,
                                          AgentTraceService traceService) {
        this.activityPlanService = Objects.requireNonNull(activityPlanService, "activityPlanService");
        this.traceService = traceService;
    }

    @Tool(
            name = "expand_plan_candidates",
            description = "Expand candidates for one existing planning period when the current legal pool poorly covers the user's soft goal."
    )
    public PlanningDiscoveryToolResult expand(
            @ToolParam(name = "period", description = "One server-defined planning period already present in the current plan")
            String period,
            @ToolParam(name = "retrievalIntent", description = "Soft semantic goal for additional candidates")
            String retrievalIntent,
            AgentDecisionToolContext context
    ) {
        Objects.requireNonNull(context, "context");
        if (!context.planning()) {
            throw new CityException("expand_plan_candidates 仅允许 PlanningAgent 使用");
        }

        PlanningToolContext planning = context.planningToolContext();
        String safePeriod = period == null ? "" : period.trim();
        if (!planning.windows().contains(safePeriod)) {
            throw new CityException("只能扩展服务器已定义的规划窗口: " + safePeriod);
        }

        planning.evidenceRegistry().beginDiscovery();
        VerifiedRequestContext verified = planning.verifiedRequestContext();

        LinkedHashSet<Long> exclusions = new LinkedHashSet<>(
                verified.hardConstraints().excludedActivityIds());
        exclusions.addAll(planning.evidenceRegistry().exposedActivityIds());

        ActivityPlanService.PlannedActivity expanded = activityPlanService.expandWindow(
                verified.sourceMode(),
                verified.userId(),
                verified.effectiveSlots(),
                verified.hardConstraints().excludedSlots(),
                safePeriod,
                verified.hardConstraints().timeConstraint(),
                verified.weather(),
                retrievalIntent,
                new ArrayList<>(exclusions)
        );

        planning.evidenceRegistry().record(List.of(expanded));
        planning.notebook().recordDiscovery(planning.evidenceRegistry().periods());
        PlanningDiscoveryToolResult result = PlanningDiscoveryToolResult.from(List.of(expanded));

        if (traceService != null) {
            traceService.recordEventForTrace(
                    verified.traceId(),
                    "PLANNING_CANDIDATE_EXPANSION_TOOL_CALLED",
                    "TOOL",
                    Map.of(
                            "period", safePeriod,
                            "retrievalIntent", retrievalIntent == null ? "" : retrievalIntent.trim(),
                            "discoveryCall", planning.evidenceRegistry().discoveryCalls()
                    ),
                    Map.of(
                            "candidateCount", result.windows().isEmpty()
                                    ? 0
                                    : result.windows().getFirst().candidates().size(),
                            "exposedActivityIds", planning.evidenceRegistry().exposedActivityIds()
                    )
            );
        }
        return result;
    }
}

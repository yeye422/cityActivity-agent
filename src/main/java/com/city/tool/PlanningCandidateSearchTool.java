package com.city.tool;

import com.city.exception.CityException;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.PlanningHorizon;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.PlanningDiscoveryToolResult;
import com.city.service.plan.ActivityPlanService;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** PlanningAgent 在发现具体时间缺口后按需补充候选。 */
@Component
public class PlanningCandidateSearchTool {

    private final ActivityPlanService activityPlanService;
    private final AgentTraceService traceService;

    public PlanningCandidateSearchTool(ActivityPlanService activityPlanService,
                                       AgentTraceService traceService) {
        this.activityPlanService = Objects.requireNonNull(activityPlanService, "activityPlanService");
        this.traceService = traceService;
    }

    @Tool(
            name = "search_plan_candidates",
            description = "Search additional legal planning candidates for a specific missing time range inside the server-defined planning horizon."
    )
    public PlanningDiscoveryToolResult search(
            @ToolParam(name = "startAt", description = "Missing range start, ISO-8601 local date-time such as 2026-09-27T15:00:00")
            String startAt,
            @ToolParam(name = "endAt", description = "Missing range end, ISO-8601 local date-time such as 2026-09-27T18:00:00")
            String endAt,
            @ToolParam(name = "retrievalIntent", description = "Soft semantic goal for the missing range")
            String retrievalIntent,
            AgentDecisionToolContext context
    ) {
        Objects.requireNonNull(context, "context");
        if (!context.planning()) {
            throw new CityException("search_plan_candidates 仅允许 PlanningAgent 使用");
        }

        PlanningToolContext planning = context.planningToolContext();
        PlanningHorizon.Range requested = parseRange(startAt, endAt);
        if (!planning.horizon().contains(requested)) {
            throw new CityException("补充检索时间必须完全位于服务器允许的 PlanningHorizon 内: " + requested);
        }

        planning.evidenceRegistry().beginDiscovery();
        VerifiedRequestContext verified = planning.verifiedRequestContext();

        LinkedHashSet<Long> exclusions = new LinkedHashSet<>(
                verified.hardConstraints().excludedActivityIds());
        exclusions.addAll(planning.evidenceRegistry().exposedActivityIds());

        ActivityPlanService.CandidateBatch batch = activityPlanService.discoverRange(
                verified.sourceMode(),
                verified.userId(),
                verified.effectiveSlots(),
                verified.hardConstraints().excludedSlots(),
                requested,
                verified.weather(),
                retrievalIntent,
                new ArrayList<>(exclusions)
        );

        planning.evidenceRegistry().record(List.of(batch));
        planning.notebook().recordDiscovery(List.of(requested));

        PlanningDiscoveryToolResult result = PlanningDiscoveryToolResult.from(
                List.of(requested),
                batch.candidates(),
                batch.sessionsByActivityId()
        );

        if (traceService != null) {
            traceService.recordEventForTrace(
                    verified.traceId(),
                    "PLANNING_CANDIDATE_SEARCH_TOOL_CALLED",
                    "TOOL",
                    Map.of(
                            "startAt", requested.startAt(),
                            "endAt", requested.endAt(),
                            "retrievalIntent", retrievalIntent == null ? "" : retrievalIntent.trim(),
                            "discoveryCall", planning.evidenceRegistry().discoveryCalls()
                    ),
                    Map.of(
                            "candidateCount", result.candidates().size(),
                            "exposedActivityIds", planning.evidenceRegistry().exposedActivityIds()
                    )
            );
        }
        return result;
    }

    private PlanningHorizon.Range parseRange(String startAt, String endAt) {
        try {
            return new PlanningHorizon.Range(
                    LocalDateTime.parse(startAt == null ? "" : startAt.trim()),
                    LocalDateTime.parse(endAt == null ? "" : endAt.trim())
            );
        } catch (DateTimeParseException | IllegalArgumentException error) {
            throw new CityException("startAt/endAt 必须是合法 ISO-8601 时间且 startAt < endAt");
        }
    }
}

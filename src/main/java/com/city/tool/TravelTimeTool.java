package com.city.tool;

import com.city.exception.CityException;
import com.city.model.ActivitySessionResponse;
import com.city.model.TravelTimeEvidence;
import com.city.model.context.PlanningToolContext;
import com.city.service.location.AmapTravelTimeService;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** PlanningAgent 的受控路线时长 Tool，只允许查询本轮已暴露的真实场次。 */
@Component
public class TravelTimeTool {

    private final AmapTravelTimeService travelTimeService;
    private final AgentTraceService traceService;

    public TravelTimeTool(AmapTravelTimeService travelTimeService) {
        this(travelTimeService, null);
    }

    @Autowired
    public TravelTimeTool(AmapTravelTimeService travelTimeService,
                          AgentTraceService traceService) {
        this.travelTimeService = Objects.requireNonNull(travelTimeService, "travelTimeService");
        this.traceService = traceService;
    }

    @Tool(
            name = "get_travel_time",
            description = "Get verified travel duration between two sessions previously returned by discover_plan_candidates."
    )
    public TravelTimeEvidence getTravelTime(
            @ToolParam(name = "fromPeriod", description = "Source planning period") String fromPeriod,
            @ToolParam(name = "fromActivityId", description = "Source activityId from discovered candidates") Long fromActivityId,
            @ToolParam(name = "fromSessionId", description = "Source sessionId from discovered candidates") Long fromSessionId,
            @ToolParam(name = "toPeriod", description = "Destination planning period") String toPeriod,
            @ToolParam(name = "toActivityId", description = "Destination activityId from discovered candidates") Long toActivityId,
            @ToolParam(name = "toSessionId", description = "Destination sessionId from discovered candidates") Long toSessionId,
            PlanningToolContext planningContext
    ) {
        Objects.requireNonNull(planningContext, "planningContext");
        planningContext.evidenceRegistry().beginTravelLookup();

        ActivitySessionResponse from = planningContext.evidenceRegistry()
                .session(fromPeriod, fromActivityId, fromSessionId);
        ActivitySessionResponse to = planningContext.evidenceRegistry()
                .session(toPeriod, toActivityId, toSessionId);
        if (from == null || to == null) {
            throw new CityException("路线查询只能引用 discover_plan_candidates 已暴露的真实场次");
        }

        if (traceService != null) {
            traceService.recordEvent(
                    "TRAVEL_TIME_TOOL_CALLED",
                    "TOOL",
                    Map.of(
                            "fromSessionId", fromSessionId,
                            "fromVenueId", from.venueId(),
                            "toSessionId", toSessionId,
                            "toVenueId", to.venueId(),
                            "travelCall", planningContext.evidenceRegistry().travelCalls()
                    ),
                    null
            );
        }

        TravelTimeEvidence evidence = travelTimeService.resolve(from, to);
        planningContext.evidenceRegistry().recordTravelEvidence(evidence);

        if (traceService != null) {
            traceService.recordEvent(
                    "TRAVEL_TIME_EVIDENCE_READY",
                    "TOOL",
                    Map.of("fromVenueId", from.venueId(), "toVenueId", to.venueId()),
                    evidence
            );
        }
        return evidence;
    }
}

package com.city.tool;

import com.city.exception.CityException;
import com.city.model.ActivitySessionResponse;
import com.city.model.TravelTimeEvidence;
import com.city.model.context.PlanningToolContext;
import com.city.service.location.AmapTravelTimeService;
import com.city.service.trace.AgentTraceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Planning 的受控路线时长 Java 边界，只允许查询本轮已暴露的真实场次。 */
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

    public TravelTimeEvidence getTravelTime(
            String fromPeriod,
            Long fromActivityId,
            Long fromSessionId,
            String toPeriod,
            Long toActivityId,
            Long toSessionId,
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
            traceService.recordEventForTrace(
                    planningContext.verifiedRequestContext().traceId(),
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
        planningContext.notebook().recordTravelLookup();

        if (traceService != null) {
            traceService.recordEventForTrace(
                    planningContext.verifiedRequestContext().traceId(),
                    "TRAVEL_TIME_EVIDENCE_READY",
                    "TOOL",
                    Map.of("fromVenueId", from.venueId(), "toVenueId", to.venueId(),
                            "notebook", planningContext.notebook().snapshot()),
                    evidence
            );
        }
        return evidence;
    }
}

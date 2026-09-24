package com.city.service.plan;

import com.city.model.DecisionResponseResult;
import com.city.model.SessionState;
import com.city.model.TravelTimeEvidence;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.context.SemanticContext;
import com.city.model.context.PlanningHorizon;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.context.SemanticContextBuilder;
import com.city.service.trace.AgentTraceService;
import com.city.service.worker.PlanningAgentWorker;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** PlanningAgent 唯一在线业务入口。 */
@Service
public class PlanningDecisionFacade {

    private final SemanticContextBuilder semanticContextBuilder;
    private final PlanningAgentWorker planningAgentWorker;
    private final PlanningResponseGeneratorService responseGenerator;
    private final AgentTraceService traceService;

    public PlanningDecisionFacade(
            SemanticContextBuilder semanticContextBuilder,
            PlanningAgentWorker planningAgentWorker,
            PlanningResponseGeneratorService responseGenerator,
            AgentTraceService traceService
    ) {
        this.semanticContextBuilder = Objects.requireNonNull(semanticContextBuilder, "semanticContextBuilder");
        this.planningAgentWorker = Objects.requireNonNull(planningAgentWorker, "planningAgentWorker");
        this.responseGenerator = Objects.requireNonNull(responseGenerator, "responseGenerator");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
    }

    public DecisionResponseResult plan(
            String userInput,
            String traceId,
            SessionState state,
            PlanningHorizon horizon,
            WeatherRecommendationContext weather,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        Objects.requireNonNull(state, "state");
        SemanticContext semanticContext = semanticContextBuilder.build(state);
        VerifiedRequestContext verifiedContext = VerifiedRequestContext.from(state, traceId, semanticContext, weather);
        PlanningHorizon safeHorizon = horizon == null ? PlanningHorizon.empty() : horizon;
        List<TravelTimeEvidence> safeTravel = travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);

        traceService.recordEvent(
                "PLANNING_REACT_ROUTE_SELECTED", "PLAN", state,
                java.util.Map.of("semanticContext", semanticContext, "horizon", safeHorizon));
        try {
            PlanningAgentExecutionResult execution = planningAgentWorker.execute(
                    userInput, verifiedContext, safeHorizon, safeTravel);
            DecisionResponseResult generated = responseGenerator.generate(
                    state.sessionId(), userInput, state.sourceMode(), state.slots(), execution, weather);
            traceService.recordEvent(
                    "PLANNING_REACT_COMPLETED", "PLAN", execution.decision(), execution.acceptedPlan());
            return generated;
        } catch (RuntimeException error) {
            traceService.recordError(
                    "PLANNING_REACT_FAILED", "PLAN",
                    java.util.Map.of("semanticContext", semanticContext, "horizon", safeHorizon), error);
            throw error;
        }
    }
}

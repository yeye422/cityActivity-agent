package com.city.service.plan;

import com.city.model.DecisionResponseResult;
import com.city.model.SessionState;
import com.city.model.TravelTimeEvidence;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.context.SemanticContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.context.SemanticContextBuilder;
import com.city.service.recommend.RecommendResponseAgentService;
import com.city.service.trace.AgentTraceService;
import com.city.service.worker.PlanningAgentWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PlanningAgent 的业务入口。 */
@Service
public class PlanningDecisionFacade {

    private final SemanticContextBuilder semanticContextBuilder;
    private final PlanningAgentWorker planningAgentWorker;
    private final PlanningResponseGeneratorService responseGenerator;
    private final AgentTraceService traceService;
    private final boolean legacyFeatureEnabled;

    public PlanningDecisionFacade(
            SemanticContextBuilder semanticContextBuilder,
            PlanningAgentWorker planningAgentWorker,
            PlanningResponseGeneratorService responseGenerator,
            AgentTraceService traceService,
            @Value("${city.agent.planning-react.enabled:false}") boolean legacyFeatureEnabled
    ) {
        this.semanticContextBuilder = Objects.requireNonNull(semanticContextBuilder, "semanticContextBuilder");
        this.planningAgentWorker = Objects.requireNonNull(planningAgentWorker, "planningAgentWorker");
        this.responseGenerator = Objects.requireNonNull(responseGenerator, "responseGenerator");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
        this.legacyFeatureEnabled = legacyFeatureEnabled;
    }

    /** 最终主链 API：始终执行 PlanningAgent，异常由上层降级策略处理。 */
    public DecisionResponseResult plan(
            String userInput,
            String traceId,
            SessionState state,
            List<String> windows,
            WeatherRecommendationContext weather,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        Objects.requireNonNull(state, "state");
        SemanticContext semanticContext = semanticContextBuilder.build(state);
        VerifiedRequestContext verifiedContext = VerifiedRequestContext.from(state, traceId, semanticContext, weather);
        List<String> safeWindows = windows == null ? List.of() : List.copyOf(windows);
        List<TravelTimeEvidence> safeTravel = travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);

        traceService.recordEvent(
                "PLANNING_REACT_ROUTE_SELECTED", "PLAN", state,
                java.util.Map.of("semanticContext", semanticContext, "windows", safeWindows));
        try {
            PlanningAgentExecutionResult execution = planningAgentWorker.execute(
                    userInput, verifiedContext, safeWindows, safeTravel);
            DecisionResponseResult generated = responseGenerator.generate(
                    state.sessionId(), userInput, state.sourceMode(), state.slots(), execution, weather);
            traceService.recordEvent(
                    "PLANNING_REACT_COMPLETED", "PLAN", execution.decision(), execution.acceptedPlan());
            return generated;
        } catch (RuntimeException error) {
            traceService.recordError(
                    "PLANNING_REACT_FAILED", "PLAN",
                    java.util.Map.of("semanticContext", semanticContext, "windows", safeWindows), error);
            throw error;
        }
    }

    /** 仅供旧 Supervisor 迁移期兼容；最终替换后删除。 */
    @Deprecated
    public Optional<RecommendResponseAgentService.Result> tryPlan(
            String userInput,
            String traceId,
            SessionState state,
            List<String> windows,
            WeatherRecommendationContext weather,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        if (!legacyFeatureEnabled || state == null) return Optional.empty();
        try {
            DecisionResponseResult result = plan(userInput, traceId, state, windows, weather, travelTimeEvidence);
            return Optional.of(new RecommendResponseAgentService.Result(result.recommend(), result.response()));
        } catch (RuntimeException error) {
            traceService.recordError("PLANNING_REACT_FALLBACK", "PLAN", state, error);
            return Optional.empty();
        }
    }
}

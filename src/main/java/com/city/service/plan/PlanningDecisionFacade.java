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

/** PlanningAgent 主链入口；迁移阶段仍保留 feature flag/fallback 兼容。 */
@Service
public class PlanningDecisionFacade {

    private final SemanticContextBuilder semanticContextBuilder;
    private final PlanningAgentWorker planningAgentWorker;
    private final PlanningResponseGeneratorService responseGenerator;
    private final AgentTraceService traceService;
    private final boolean enabled;

    public PlanningDecisionFacade(
            SemanticContextBuilder semanticContextBuilder,
            PlanningAgentWorker planningAgentWorker,
            PlanningResponseGeneratorService responseGenerator,
            AgentTraceService traceService,
            @Value("${city.agent.planning-react.enabled:false}") boolean enabled
    ) {
        this.semanticContextBuilder = Objects.requireNonNull(semanticContextBuilder, "semanticContextBuilder");
        this.planningAgentWorker = Objects.requireNonNull(planningAgentWorker, "planningAgentWorker");
        this.responseGenerator = Objects.requireNonNull(responseGenerator, "responseGenerator");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
        this.enabled = enabled;
    }

    /**
     * 过渡兼容：Supervisor 仍使用旧 Result 类型；新 ResponseGenerator 已完全独立。
     * 最终薄 Supervisor 切换后会移除此适配并直接返回 DecisionResponseResult。
     */
    public Optional<RecommendResponseAgentService.Result> tryPlan(
            String userInput,
            String traceId,
            SessionState state,
            List<String> windows,
            WeatherRecommendationContext weather,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        if (!enabled || state == null) return Optional.empty();

        SemanticContext semanticContext = semanticContextBuilder.build(state);
        VerifiedRequestContext verifiedContext = VerifiedRequestContext.from(state, traceId, semanticContext, weather);
        List<String> safeWindows = windows == null ? List.of() : List.copyOf(windows);
        List<TravelTimeEvidence> safeTravel = travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);

        traceService.recordEvent(
                "PLANNING_REACT_ROUTE_SELECTED",
                "PLAN",
                state,
                java.util.Map.of("semanticContext", semanticContext, "windows", safeWindows));

        try {
            PlanningAgentExecutionResult execution = planningAgentWorker.execute(
                    userInput, verifiedContext, safeWindows, safeTravel);
            DecisionResponseResult generated = responseGenerator.generate(
                    state.sessionId(), userInput, state.sourceMode(), state.slots(), execution, weather);
            traceService.recordEvent(
                    "PLANNING_REACT_COMPLETED", "PLAN", execution.decision(), execution.acceptedPlan());
            return Optional.of(new RecommendResponseAgentService.Result(
                    generated.recommend(), generated.response()));
        } catch (RuntimeException error) {
            traceService.recordError(
                    "PLANNING_REACT_FALLBACK",
                    "PLAN",
                    java.util.Map.of("semanticContext", semanticContext, "windows", safeWindows),
                    error);
            return Optional.empty();
        }
    }
}

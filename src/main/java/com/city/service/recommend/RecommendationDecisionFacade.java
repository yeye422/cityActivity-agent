package com.city.service.recommend;

import com.city.model.DecisionResponseResult;
import com.city.model.SessionState;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.RecommendationExecutionResult;
import com.city.model.context.SemanticContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.context.SemanticContextBuilder;
import com.city.service.trace.AgentTraceService;
import com.city.service.worker.RecommendationWorker;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** RecommendationAgent 唯一在线业务入口。 */
@Service
public class RecommendationDecisionFacade {

    private final SemanticContextBuilder semanticContextBuilder;
    private final RecommendationWorker recommendationWorker;
    private final RecommendationResponseGeneratorService responseGenerator;
    private final AgentTraceService traceService;

    public RecommendationDecisionFacade(
            SemanticContextBuilder semanticContextBuilder,
            RecommendationWorker recommendationWorker,
            RecommendationResponseGeneratorService responseGenerator,
            AgentTraceService traceService
    ) {
        this.semanticContextBuilder = Objects.requireNonNull(semanticContextBuilder, "semanticContextBuilder");
        this.recommendationWorker = Objects.requireNonNull(recommendationWorker, "recommendationWorker");
        this.responseGenerator = Objects.requireNonNull(responseGenerator, "responseGenerator");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
    }

    public DecisionResponseResult recommend(
            String userInput,
            String traceId,
            SessionState state,
            List<Long> excludeActivityIds,
            WeatherRecommendationContext weather
    ) {
        Objects.requireNonNull(state, "state");
        List<Long> safeExcludeIds = excludeActivityIds == null ? List.of() : List.copyOf(excludeActivityIds);
        SessionState decisionState = state.withLastRecommendations(safeExcludeIds);
        SemanticContext semanticContext = semanticContextBuilder.build(decisionState);
        VerifiedRequestContext verifiedContext = VerifiedRequestContext.from(
                decisionState, traceId, semanticContext, weather);

        traceService.recordEvent("RECOMMENDATION_REACT_ROUTE_SELECTED", "RECOMMEND", state, semanticContext);
        try {
            RecommendationExecutionResult execution = recommendationWorker.execute(userInput, verifiedContext);
            DecisionResponseResult generated = responseGenerator.generate(
                    state.sessionId(), userInput, state.sourceMode(), state.slots(), execution, weather);
            traceService.recordEvent(
                    "RECOMMENDATION_REACT_COMPLETED", "RECOMMEND", execution.decision(), generated.recommend());
            return generated;
        } catch (RuntimeException error) {
            traceService.recordError("RECOMMENDATION_REACT_FAILED", "RECOMMEND", semanticContext, error);
            throw error;
        }
    }
}

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** RecommendationAgent 主链入口；迁移阶段仍保留 feature flag/fallback 兼容。 */
@Service
public class RecommendationDecisionFacade {

    private final SemanticContextBuilder semanticContextBuilder;
    private final RecommendationWorker recommendationWorker;
    private final RecommendationResponseGeneratorService responseGenerator;
    private final AgentTraceService traceService;
    private final boolean enabled;

    public RecommendationDecisionFacade(
            SemanticContextBuilder semanticContextBuilder,
            RecommendationWorker recommendationWorker,
            RecommendationResponseGeneratorService responseGenerator,
            AgentTraceService traceService,
            @Value("${city.agent.recommendation-react.enabled:false}") boolean enabled
    ) {
        this.semanticContextBuilder = Objects.requireNonNull(semanticContextBuilder, "semanticContextBuilder");
        this.recommendationWorker = Objects.requireNonNull(recommendationWorker, "recommendationWorker");
        this.responseGenerator = Objects.requireNonNull(responseGenerator, "responseGenerator");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
        this.enabled = enabled;
    }

    public Optional<DecisionResponseResult> tryRecommend(
            String userInput,
            String traceId,
            SessionState state,
            List<Long> excludeActivityIds,
            WeatherRecommendationContext weather
    ) {
        if (!enabled || state == null) {
            return Optional.empty();
        }

        List<Long> safeExcludeIds = excludeActivityIds == null ? List.of() : List.copyOf(excludeActivityIds);
        SessionState decisionState = state.withLastRecommendations(safeExcludeIds);
        SemanticContext semanticContext = semanticContextBuilder.build(decisionState);
        VerifiedRequestContext verifiedContext = VerifiedRequestContext.from(
                decisionState,
                traceId,
                semanticContext,
                weather
        );

        traceService.recordEvent("RECOMMENDATION_REACT_ROUTE_SELECTED", "RECOMMEND", state, semanticContext);

        try {
            RecommendationExecutionResult execution = recommendationWorker.execute(userInput, verifiedContext);
            DecisionResponseResult generated = responseGenerator.generate(
                    state.sessionId(), userInput, state.sourceMode(), state.slots(), execution, weather);
            traceService.recordEvent(
                    "RECOMMENDATION_REACT_COMPLETED", "RECOMMEND", execution.decision(), generated.recommend());
            return Optional.of(generated);
        } catch (RuntimeException error) {
            traceService.recordError("RECOMMENDATION_REACT_FALLBACK", "RECOMMEND", semanticContext, error);
            return Optional.empty();
        }
    }
}

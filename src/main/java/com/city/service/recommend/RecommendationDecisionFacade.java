package com.city.service.recommend;

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

/**
 * 新 RecommendationAgent 主链的单一业务入口。
 *
 * <p>该 Facade 不写 SessionState。调用方传入当前已应用完本轮 Patch 的状态和本轮应排除的历史 ID，
 * Facade 负责构造模型不可修改的 VerifiedRequestContext，执行 RecommendationAgent，随后仅把 Agent
 * 已选定且经过候选白名单校验的实体交给响应生成层。</p>
 *
 * <p>迁移期间通过 city.agent.recommendation-react.enabled 控制。关闭或新链路异常时返回 Optional.empty()，
 * 由现有 Orchestrator 继续执行旧推荐链，保证可以随时回退。</p>
 */
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

    /**
     * 尝试执行新 RecommendationAgent 链路；返回 empty 表示调用方应继续旧链路。
     */
    public Optional<RecommendResponseAgentService.Result> tryRecommend(
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

        traceService.recordEvent(
                "RECOMMENDATION_REACT_ROUTE_SELECTED",
                "RECOMMEND",
                state,
                semanticContext
        );

        try {
            RecommendationExecutionResult execution = recommendationWorker.execute(userInput, verifiedContext);
            RecommendResponseAgentService.Result generated = responseGenerator.generate(
                    state.sessionId(),
                    userInput,
                    state.sourceMode(),
                    state.slots(),
                    execution,
                    weather
            );
            traceService.recordEvent(
                    "RECOMMENDATION_REACT_COMPLETED",
                    "RECOMMEND",
                    execution.decision(),
                    generated.recommend()
            );
            return Optional.of(generated);
        } catch (RuntimeException error) {
            traceService.recordError(
                    "RECOMMENDATION_REACT_FALLBACK",
                    "RECOMMEND",
                    semanticContext,
                    error
            );
            return Optional.empty();
        }
    }
}

package com.city.service.recommend;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.DecisionResponseResult;
import com.city.model.RecommendResult;
import com.city.model.ResponseResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.RecommendationDecision;
import com.city.model.agent.RecommendationExecutionResult;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.context.SemanticContextBuilder;
import com.city.service.trace.AgentTraceService;
import com.city.service.worker.RecommendationWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationDecisionFacadeTest {

    @Mock
    RecommendationWorker recommendationWorker;
    @Mock
    RecommendationResponseGeneratorService responseGenerator;
    @Mock
    AgentTraceService traceService;

    private final SemanticContextBuilder semanticContextBuilder = new SemanticContextBuilder();

    @Test
    void shouldReturnEmptyWithoutCallingAgentWhenFeatureDisabled() {
        RecommendationDecisionFacade facade = new RecommendationDecisionFacade(
                semanticContextBuilder, recommendationWorker, responseGenerator, traceService, false);

        Optional<RecommendResponseAgentService.Result> result = facade.tryRecommend(
                "想约会", "trace-1", state(), List.of(88L), WeatherRecommendationContext.inactive());

        assertTrue(result.isEmpty());
        verify(recommendationWorker, never()).execute(any(), any());
    }

    @Test
    void shouldExecuteAgentAndGenerateResponseWhenEnabled() {
        RecommendationDecisionFacade facade = new RecommendationDecisionFacade(
                semanticContextBuilder, recommendationWorker, responseGenerator, traceService, true);
        SessionState state = state();
        ActivityItem selected = new ActivityItem(
                101L, SourceMode.PUBLIC, null, "双人陶艺", state.slots(),
                null, null, null, null, 120, 0.9);
        RecommendationDecision decision = new RecommendationDecision(
                List.of(101L),
                List.of(new RecommendationDecision.CandidateAssessment(101L, "HIGH", "互动性高")),
                true, "优先互动体验", 0.9);
        RecommendationExecutionResult execution = new RecommendationExecutionResult(
                decision, List.of(selected), 1, List.of());
        DecisionResponseResult generated = new DecisionResponseResult(
                RecommendResult.empty(), ResponseResult.textOnly("推荐双人陶艺"));

        when(recommendationWorker.execute(eq("想约会"), any(VerifiedRequestContext.class)))
                .thenReturn(execution);
        when(responseGenerator.generate(
                eq(state.sessionId()), eq("想约会"), eq(SourceMode.PUBLIC), eq(state.slots()),
                eq(execution), any(WeatherRecommendationContext.class)))
                .thenReturn(generated);

        Optional<RecommendResponseAgentService.Result> result = facade.tryRecommend(
                "想约会", "trace-1", state, List.of(88L), WeatherRecommendationContext.inactive());

        assertTrue(result.isPresent());
        assertEquals(generated.recommend(), result.get().recommend());
        assertEquals(generated.response(), result.get().response());
        verify(recommendationWorker).execute(eq("想约会"), any(VerifiedRequestContext.class));
    }

    @Test
    void shouldFallbackToLegacyPathWhenAgentFails() {
        RecommendationDecisionFacade facade = new RecommendationDecisionFacade(
                semanticContextBuilder, recommendationWorker, responseGenerator, traceService, true);
        when(recommendationWorker.execute(any(), any())).thenThrow(new IllegalStateException("agent failed"));

        Optional<RecommendResponseAgentService.Result> result = facade.tryRecommend(
                "想约会", "trace-1", state(), List.of(), WeatherRecommendationContext.inactive());

        assertTrue(result.isEmpty());
        verify(responseGenerator, never()).generate(any(), any(), any(), any(), any(), any());
    }

    private SessionState state() {
        SlotBundle slots = new SlotBundle(
                List.of("上海"), List.of("浦东"), List.of("新鲜"), List.of("情侣"),
                List.of("200元内"), List.of(), List.of("轻松"), List.of(), List.of("室内"));
        return SessionState.fresh("session-1", 9L, SourceMode.PUBLIC).withSlots(slots);
    }
}

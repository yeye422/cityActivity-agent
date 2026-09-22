package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.DecisionResponseResult;
import com.city.model.PlanCandidate;
import com.city.model.RecommendResult;
import com.city.model.ResponseResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanValidationResult;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.agent.PlanningDecision;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.context.SemanticContextBuilder;
import com.city.service.recommend.RecommendResponseAgentService;
import com.city.service.trace.AgentTraceService;
import com.city.service.worker.PlanningAgentWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
class PlanningDecisionFacadeTest {

    @Mock
    PlanningAgentWorker planningAgentWorker;
    @Mock
    PlanningResponseGeneratorService responseGenerator;
    @Mock
    AgentTraceService traceService;

    private final SemanticContextBuilder semanticContextBuilder = new SemanticContextBuilder();

    @Test
    void shouldReturnEmptyWithoutCallingAgentWhenFeatureDisabled() {
        PlanningDecisionFacade facade = new PlanningDecisionFacade(
                semanticContextBuilder, planningAgentWorker, responseGenerator, traceService, false);

        Optional<RecommendResponseAgentService.Result> result = facade.tryPlan(
                "下午到晚上安排约会", "trace-1", state(),
                List.of("14:00-16:00", "18:00-20:00"),
                WeatherRecommendationContext.inactive(), List.of());

        assertTrue(result.isEmpty());
        verify(planningAgentWorker, never()).execute(any(), any(), any(), any());
    }

    @Test
    void shouldExecuteAgentAndGenerateResponseWhenEnabled() {
        PlanningDecisionFacade facade = new PlanningDecisionFacade(
                semanticContextBuilder, planningAgentWorker, responseGenerator, traceService, true);
        SessionState state = state();
        ActivityItem activity = new ActivityItem(
                101L, SourceMode.PUBLIC, null, "双人陶艺", state.slots(),
                null, null, null, null, 120, 0.9);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item("14:00-16:00", 101L, null)));
        PlanningDecision decision = new PlanningDecision(proposal, "下午安排互动体验", 0.9);
        PlanCandidate accepted = new PlanCandidate(
                List.of(new PlanCandidate.Item("14:00-16:00", activity, null)), BigDecimal.ZERO);
        PlanningAgentExecutionResult execution = new PlanningAgentExecutionResult(
                decision, accepted, PlanValidationResult.valid(accepted));
        DecisionResponseResult generated = new DecisionResponseResult(
                RecommendResult.empty(), ResponseResult.textOnly("下午可以安排双人陶艺"));

        when(planningAgentWorker.execute(
                eq("下午到晚上安排约会"), any(VerifiedRequestContext.class), any(), any()))
                .thenReturn(execution);
        when(responseGenerator.generate(
                eq(state.sessionId()), eq("下午到晚上安排约会"), eq(SourceMode.PUBLIC),
                eq(state.slots()), eq(execution), any(WeatherRecommendationContext.class)))
                .thenReturn(generated);

        Optional<RecommendResponseAgentService.Result> result = facade.tryPlan(
                "下午到晚上安排约会", "trace-1", state,
                List.of("14:00-16:00", "18:00-20:00"),
                WeatherRecommendationContext.inactive(), List.of());

        assertTrue(result.isPresent());
        assertEquals(generated.recommend(), result.get().recommend());
        assertEquals(generated.response(), result.get().response());
    }

    @Test
    void shouldFallbackToLegacyPathWhenAgentFails() {
        PlanningDecisionFacade facade = new PlanningDecisionFacade(
                semanticContextBuilder, planningAgentWorker, responseGenerator, traceService, true);
        when(planningAgentWorker.execute(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("agent failed"));

        Optional<RecommendResponseAgentService.Result> result = facade.tryPlan(
                "下午到晚上安排约会", "trace-1", state(), List.of("14:00-16:00"),
                WeatherRecommendationContext.inactive(), List.of());

        assertTrue(result.isEmpty());
        verify(responseGenerator, never()).generate(any(), any(), any(), any(), any(), any());
    }

    private SessionState state() {
        SlotBundle slots = new SlotBundle(
                List.of("上海"), List.of("浦东"), List.of("互动"), List.of("情侣"),
                List.of("500元内"), List.of(), List.of("轻松"), List.of(), List.of("室内"));
        return SessionState.fresh("session-plan", 9L, SourceMode.PUBLIC).withSlots(slots);
    }
}

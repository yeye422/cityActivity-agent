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
import com.city.model.context.PlanningHorizon;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.context.SemanticContextBuilder;
import com.city.service.trace.AgentTraceService;
import com.city.service.worker.PlanningAgentWorker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningDecisionFacadeTest {

    @Test
    void shouldExecuteAgentAndGenerateStructuredResponse() {
        PlanningAgentWorker worker = mock(PlanningAgentWorker.class);
        PlanningResponseGeneratorService generator = mock(PlanningResponseGeneratorService.class);
        PlanningDecisionFacade facade = new PlanningDecisionFacade(
                new SemanticContextBuilder(), worker, generator, mock(AgentTraceService.class));
        SessionState state = state();
        ActivityItem activity = new ActivityItem(
                101L, SourceMode.PUBLIC, null, "双人陶艺", state.slots(),
                null, null, null, null, 120, 0.9);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item(101L, null, start(), end())));
        PlanningDecision decision = new PlanningDecision(proposal, "下午安排互动体验", 0.9);
        PlanCandidate accepted = new PlanCandidate(
                List.of(new PlanCandidate.Item(activity, null, start(), end())), BigDecimal.ZERO);
        PlanningAgentExecutionResult execution = new PlanningAgentExecutionResult(
                decision, accepted, PlanValidationResult.valid(accepted));
        DecisionResponseResult generated = new DecisionResponseResult(
                RecommendResult.empty(), ResponseResult.textOnly("下午可以安排双人陶艺"));

        when(worker.execute(
                eq("下午到晚上安排约会"), any(VerifiedRequestContext.class), any(), any()))
                .thenReturn(execution);
        when(generator.generate(
                eq(state.sessionId()), eq("下午到晚上安排约会"), eq(SourceMode.PUBLIC),
                eq(state.slots()), eq(execution), any(WeatherRecommendationContext.class)))
                .thenReturn(generated);

        DecisionResponseResult result = facade.plan(
                "下午到晚上安排约会", "trace-1", state,
                horizon(),
                WeatherRecommendationContext.inactive(), List.of());

        assertEquals(generated, result);
    }

    @Test
    void shouldPropagateAgentFailureToWorkflowDegradedPath() {
        PlanningAgentWorker worker = mock(PlanningAgentWorker.class);
        PlanningDecisionFacade facade = new PlanningDecisionFacade(
                new SemanticContextBuilder(), worker,
                mock(PlanningResponseGeneratorService.class), mock(AgentTraceService.class));
        when(worker.execute(any(), any(), any(), any())).thenThrow(new IllegalStateException("agent failed"));

        assertThrows(IllegalStateException.class, () -> facade.plan(
                "下午到晚上安排约会", "trace-1", state(), horizon(),
                WeatherRecommendationContext.inactive(), List.of()));
    }

    private PlanningHorizon horizon() {
        return new PlanningHorizon(List.of(new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, 12, 0),
                LocalDateTime.of(2026, 9, 27, 23, 0))));
    }

    private LocalDateTime start() {
        return LocalDateTime.of(2026, 9, 27, 14, 0);
    }

    private LocalDateTime end() {
        return LocalDateTime.of(2026, 9, 27, 16, 0);
    }

    private SessionState state() {
        SlotBundle slots = new SlotBundle(
                List.of("上海"), List.of("浦东"), List.of("互动"), List.of("情侣"),
                List.of("500元内"), List.of(), List.of("轻松"), List.of(), List.of("室内"));
        return SessionState.fresh("session-plan", 9L, SourceMode.PUBLIC).withSlots(slots);
    }
}

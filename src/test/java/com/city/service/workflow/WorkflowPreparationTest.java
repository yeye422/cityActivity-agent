package com.city.service.workflow;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.SlotMutation;
import com.city.service.clarify.ClarifyRuleService;
import com.city.model.context.PlanningHorizon;
import com.city.service.plan.PlanningHorizonResolver;
import com.city.service.slot.SlotMutationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowPreparationTest {

    @Test
    void recommendShouldApplyPatchAndEnterRecommendPhaseWhenReady() {
        SlotMutationService mutationService = mock(SlotMutationService.class);
        ClarifyRuleService clarify = mock(ClarifyRuleService.class);
        SlotBundle slots = slots("西安", "展览");
        SlotMutation mutation = new SlotMutation(slots, SlotBundle.empty(), Set.of("budget"));
        when(mutationService.apply(any(), any(), any(), any())).thenReturn(mutation);
        when(clarify.missingRequiredFields(eq(Intent.ACTIVITY_RECOMMENDATION), any(), any()))
                .thenReturn(List.of());

        RecommendWorkflow.Preparation result = new RecommendWorkflow(mutationService, clarify)
                .prepare(state(), new IntentResult(Intent.ACTIVITY_RECOMMENDATION, 1.0));

        assertEquals(Intent.ACTIVITY_RECOMMENDATION, result.state().currentIntent());
        assertEquals(SessionPhase.RECOMMEND, result.state().phase());
        assertEquals(slots, result.state().slots());
        assertTrue(result.state().unconstrainedSlots().contains("budget"));
        assertEquals(null, result.missingField());
    }

    @Test
    void adjustShouldStopAtClarificationBeforeRetrieval() {
        SlotMutationService mutationService = mock(SlotMutationService.class);
        ClarifyRuleService clarify = mock(ClarifyRuleService.class);
        when(mutationService.apply(any(), any(), any(), any())).thenReturn(SlotMutation.empty());
        when(clarify.missingRequiredFields(eq(Intent.ACTIVITY_RECOMMENDATION), any(), any()))
                .thenReturn(List.of(ClarifyField.CITY));

        AdjustWorkflow.Preparation result = new AdjustWorkflow(mutationService, clarify)
                .prepare(state(), new IntentResult(Intent.ACTIVITY_ADJUST, 1.0));

        assertEquals(Intent.ACTIVITY_ADJUST, result.state().currentIntent());
        assertEquals(ClarifyField.CITY, result.missingField());
    }

    @Test
    void planningShouldResolveHorizonOnlyAfterClarificationPasses() {
        SlotMutationService mutationService = mock(SlotMutationService.class);
        ClarifyRuleService clarify = mock(ClarifyRuleService.class);
        PlanningHorizonResolver horizonResolver = mock(PlanningHorizonResolver.class);
        SlotBundle slots = slots("西安", "展览");
        when(mutationService.apply(any(), any(), any(), any()))
                .thenReturn(new SlotMutation(slots, SlotBundle.empty(), Set.of()));
        when(clarify.missingRequiredFields(eq(Intent.ACTIVITY_PLAN), any(), any()))
                .thenReturn(List.of());
        PlanningHorizon horizon = new PlanningHorizon(List.of(new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, 14, 0),
                LocalDateTime.of(2026, 9, 27, 20, 0))));
        when(horizonResolver.resolve(any())).thenReturn(horizon);

        PlanningWorkflow.Preparation result = new PlanningWorkflow(mutationService, clarify, horizonResolver)
                .prepare(state(), new IntentResult(Intent.ACTIVITY_PLAN, 1.0));

        assertEquals(SessionPhase.PLAN, result.state().phase());
        assertEquals(horizon, result.horizon());
    }

    @Test
    void planningShouldNotResolveHorizonWhenClarificationIsRequired() {
        SlotMutationService mutationService = mock(SlotMutationService.class);
        ClarifyRuleService clarify = mock(ClarifyRuleService.class);
        PlanningHorizonResolver horizonResolver = mock(PlanningHorizonResolver.class);
        when(mutationService.apply(any(), any(), any(), any())).thenReturn(SlotMutation.empty());
        when(clarify.missingRequiredFields(eq(Intent.ACTIVITY_PLAN), any(), any()))
                .thenReturn(List.of(ClarifyField.CITY));

        PlanningWorkflow.Preparation result = new PlanningWorkflow(mutationService, clarify, horizonResolver)
                .prepare(state(), new IntentResult(Intent.ACTIVITY_PLAN, 1.0));

        assertEquals(ClarifyField.CITY, result.missingField());
        assertTrue(result.horizon().isEmpty());
        verify(horizonResolver, never()).resolve(any());
    }

    private SessionState state() {
        return SessionState.fresh("session-test", 1L, SourceMode.PUBLIC);
    }

    private SlotBundle slots(String city, String type) {
        return new SlotBundle(
                List.of(city), List.of(), List.of(), List.of(), List.of(),
                List.of(type), List.of(), List.of(), List.of());
    }
}

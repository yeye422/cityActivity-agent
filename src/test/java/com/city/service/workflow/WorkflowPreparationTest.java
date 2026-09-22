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
import com.city.service.plan.TimeWindowResolver;
import com.city.service.slot.SlotMutationService;
import org.junit.jupiter.api.Test;

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
    void planningShouldResolveWindowsOnlyAfterClarificationPasses() {
        SlotMutationService mutationService = mock(SlotMutationService.class);
        ClarifyRuleService clarify = mock(ClarifyRuleService.class);
        TimeWindowResolver timeWindowResolver = mock(TimeWindowResolver.class);
        SlotBundle slots = slots("西安", "展览");
        when(mutationService.apply(any(), any(), any(), any()))
                .thenReturn(new SlotMutation(slots, SlotBundle.empty(), Set.of()));
        when(clarify.missingRequiredFields(eq(Intent.ACTIVITY_PLAN), any(), any()))
                .thenReturn(List.of());
        when(timeWindowResolver.resolve(any(), any())).thenReturn(List.of("14:00-16:00", "18:00-20:00"));

        PlanningWorkflow.Preparation result = new PlanningWorkflow(mutationService, clarify, timeWindowResolver)
                .prepare(state(), new IntentResult(Intent.ACTIVITY_PLAN, 1.0));

        assertEquals(SessionPhase.PLAN, result.state().phase());
        assertEquals(List.of("14:00-16:00", "18:00-20:00"), result.windows());
    }

    @Test
    void planningShouldNotResolveWindowsWhenClarificationIsRequired() {
        SlotMutationService mutationService = mock(SlotMutationService.class);
        ClarifyRuleService clarify = mock(ClarifyRuleService.class);
        TimeWindowResolver timeWindowResolver = mock(TimeWindowResolver.class);
        when(mutationService.apply(any(), any(), any(), any())).thenReturn(SlotMutation.empty());
        when(clarify.missingRequiredFields(eq(Intent.ACTIVITY_PLAN), any(), any()))
                .thenReturn(List.of(ClarifyField.CITY));

        PlanningWorkflow.Preparation result = new PlanningWorkflow(mutationService, clarify, timeWindowResolver)
                .prepare(state(), new IntentResult(Intent.ACTIVITY_PLAN, 1.0));

        assertEquals(ClarifyField.CITY, result.missingField());
        assertTrue(result.windows().isEmpty());
        verify(timeWindowResolver, never()).resolve(any(), any());
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

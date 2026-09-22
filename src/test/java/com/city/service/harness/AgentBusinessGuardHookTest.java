package com.city.service.harness;

import com.city.enums.DegradationReason;
import com.city.exception.CityException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentBusinessGuardHookTest {

    @Test
    void shouldRejectToolOutsideAgentWhitelist() {
        AgentBusinessGuardHook hook = new AgentBusinessGuardHook(
                "recommendation",
                Set.of("search_activities"),
                2,
                3,
                null
        );

        assertThrows(CityException.class,
                () -> hook.beforeToolCall("validate_plan", Map.of()));
        assertEquals(0, hook.actingCalls());
    }

    @Test
    void shouldStopWhenActingBudgetExceeded() {
        AgentBusinessGuardHook hook = new AgentBusinessGuardHook(
                "recommendation",
                Set.of("search_activities"),
                2,
                3,
                null
        );

        hook.beforeToolCall("search_activities", Map.of("q", "约会"));
        hook.beforeToolCall("search_activities", Map.of("q", "互动"));

        AgentExecutionHarness.AgentHarnessException error = assertThrows(
                AgentExecutionHarness.AgentHarnessException.class,
                () -> hook.beforeToolCall("search_activities", Map.of("q", "新鲜"))
        );
        assertEquals(DegradationReason.CALL_BUDGET_EXCEEDED, error.degradationReason());
    }

    @Test
    void shouldStopRepeatedToolSignature() {
        AgentBusinessGuardHook hook = new AgentBusinessGuardHook(
                "planning",
                Set.of("validate_plan"),
                10,
                3,
                null
        );
        Map<String, Object> sameProposal = Map.of("activityId", 101L);

        hook.beforeToolCall("validate_plan", sameProposal);
        hook.beforeToolCall("validate_plan", sameProposal);

        AgentExecutionHarness.AgentHarnessException error = assertThrows(
                AgentExecutionHarness.AgentHarnessException.class,
                () -> hook.beforeToolCall("validate_plan", sameProposal)
        );
        assertEquals(DegradationReason.LOOP_DETECTED, error.degradationReason());
    }
}

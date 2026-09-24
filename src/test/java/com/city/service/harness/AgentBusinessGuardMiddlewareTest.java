package com.city.service.harness;

import com.city.enums.DegradationReason;
import com.city.exception.CityException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentBusinessGuardMiddlewareTest {

    @Test
    void shouldRejectToolOutsideAgentWhitelist() {
        AgentBusinessGuardMiddleware middleware = new AgentBusinessGuardMiddleware(
                "recommendation",
                Set.of("search_activities"),
                2,
                3,
                null
        );

        assertThrows(CityException.class,
                () -> middleware.beforeToolCall("validate_plan", Map.of()));
        assertEquals(0, middleware.actingCalls());
    }

    @Test
    void shouldAllowAgentScopeStructuredResponseFallbackWithoutConsumingBusinessBudget() {
        AgentBusinessGuardMiddleware middleware = new AgentBusinessGuardMiddleware(
                "recommendation",
                Set.of("search_activities"),
                2,
                3,
                null
        );

        middleware.beforeToolCall("search_activities", Map.of("q", "约会"));
        assertDoesNotThrow(() -> middleware.beforeToolCall(
                AgentBusinessGuardMiddleware.FRAMEWORK_RESPONSE_TOOL,
                Map.of("selectedActivityIds", java.util.List.of(1L))
        ));
        assertEquals(1, middleware.actingCalls());

        middleware.beforeToolCall("search_activities", Map.of("q", "互动"));
        assertDoesNotThrow(() -> middleware.beforeToolCall(
                AgentBusinessGuardMiddleware.FRAMEWORK_RESPONSE_TOOL,
                Map.of("selectedActivityIds", java.util.List.of(2L))
        ));
        assertEquals(2, middleware.actingCalls());
    }

    @Test
    void shouldStillRejectUnknownFrameworkLikeTool() {
        AgentBusinessGuardMiddleware middleware = new AgentBusinessGuardMiddleware(
                "recommendation",
                Set.of("search_activities"),
                2,
                3,
                null
        );

        assertThrows(CityException.class,
                () -> middleware.beforeToolCall("generate_something_else", Map.of()));
        assertEquals(0, middleware.actingCalls());
    }

    @Test
    void shouldStopWhenActingBudgetExceeded() {
        AgentBusinessGuardMiddleware middleware = new AgentBusinessGuardMiddleware(
                "recommendation",
                Set.of("search_activities"),
                2,
                3,
                null
        );

        middleware.beforeToolCall("search_activities", Map.of("q", "约会"));
        middleware.beforeToolCall("search_activities", Map.of("q", "互动"));

        AgentExecutionHarness.AgentHarnessException error = assertThrows(
                AgentExecutionHarness.AgentHarnessException.class,
                () -> middleware.beforeToolCall("search_activities", Map.of("q", "新鲜"))
        );
        assertEquals(DegradationReason.CALL_BUDGET_EXCEEDED, error.degradationReason());
    }

    @Test
    void shouldStopRepeatedToolSignature() {
        AgentBusinessGuardMiddleware middleware = new AgentBusinessGuardMiddleware(
                "planning",
                Set.of("validate_plan"),
                10,
                3,
                null
        );
        Map<String, Object> sameProposal = Map.of("activityId", 101L);

        middleware.beforeToolCall("validate_plan", sameProposal);
        middleware.beforeToolCall("validate_plan", sameProposal);

        AgentExecutionHarness.AgentHarnessException error = assertThrows(
                AgentExecutionHarness.AgentHarnessException.class,
                () -> middleware.beforeToolCall("validate_plan", sameProposal)
        );
        assertEquals(DegradationReason.LOOP_DETECTED, error.degradationReason());
    }
}

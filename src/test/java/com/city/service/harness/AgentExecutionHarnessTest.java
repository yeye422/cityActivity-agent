package com.city.service.harness;

import com.city.enums.DegradationReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentExecutionHarnessTest {

    @Test
    void repeatedIdenticalCallShouldConvergeBeforeBudgetIsExhausted() {
        AgentExecutionHarness harness = new AgentExecutionHarness(8, 3, 3, Duration.ofSeconds(30));
        try (AgentExecutionHarness.RunScope ignored = harness.openRun("session-1")) {
            assertDoesNotThrow(() -> harness.beforeCall("IntentAgent", "same-input"));
            assertDoesNotThrow(() -> harness.beforeCall("IntentAgent", "same-input"));
            AgentExecutionHarness.AgentHarnessException error = assertThrows(
                    AgentExecutionHarness.AgentHarnessException.class,
                    () -> harness.beforeCall("IntentAgent", "same-input"));
            assertEquals(DegradationReason.LOOP_DETECTED, error.degradationReason());
        }
    }

    @Test
    void failuresShouldOpenCircuitAndSuccessfulProbeShouldCloseIt() throws Exception {
        AgentExecutionHarness harness = new AgentExecutionHarness(8, 3, 2, Duration.ofMillis(50));
        AgentExecutionHarness.CallPermit first = harness.beforeCall("PlanAgent", "one");
        harness.recordFailure(first);
        AgentExecutionHarness.CallPermit second = harness.beforeCall("PlanAgent", "two");
        harness.recordFailure(second);
        assertThrows(AgentExecutionHarness.AgentHarnessException.class,
                () -> harness.beforeCall("PlanAgent", "three"));
        Thread.sleep(60);
        AgentExecutionHarness.CallPermit probe = harness.beforeCall("PlanAgent", "probe");
        harness.recordSuccess(probe);
        assertDoesNotThrow(() -> harness.beforeCall("PlanAgent", "after-recovery"));
    }
}

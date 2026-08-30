package com.city.service.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegressionGateTest {
    @Test
    void criticalMetricDropFailsGate() {
        RegressionGate.Result result = RegressionGate.evaluate(80.0, 80.5,
                Map.of("operationAccuracy", 1.0), Map.of("operationAccuracy", 0.9));
        assertFalse(result.passed());
        assertEquals(-0.1, result.metricDeltas().get("operationAccuracy"));
    }

    @Test
    void missingBaselineMetricDoesNotBlockGate() {
        RegressionGate.Result result = RegressionGate.evaluate(80.0, 79.0,
                Map.of(), Map.of("multiTurnConsistency", 0.0));
        assertTrue(result.passed());
        assertEquals(-1.0, result.scoreDelta());
    }
}

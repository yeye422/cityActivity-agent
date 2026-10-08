package com.city.service.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void reactSuccessDropFailsGate() {
        RegressionGate.Result result = RegressionGate.evaluate(82.0, 82.0,
                Map.of("recommendationReactSuccessRate", 0.98),
                Map.of("recommendationReactSuccessRate", 0.90));

        assertFalse(result.passed());
        assertEquals(-0.08, result.metricDeltas().get("recommendationReactSuccessRate"));
    }

    @Test
    void reactRouteCoverageDropFailsGate() {
        RegressionGate.Result result = RegressionGate.evaluate(82.0, 82.0,
                Map.of("reactRouteCoverage", 0.9),
                Map.of("reactRouteCoverage", 0.7));

        assertFalse(result.passed());
        assertEquals(-0.2, result.metricDeltas().get("reactRouteCoverage"));
    }

    @Test
    void reactDegradationIncreaseFailsGate() {
        RegressionGate.Result result = RegressionGate.evaluate(82.0, 82.0,
                Map.of("reactDegradationRate", 0.01),
                Map.of("reactDegradationRate", 0.05));

        assertFalse(result.passed());
        assertEquals(0.04, result.metricDeltas().get("reactDegradationRate"));
    }

    @Test
    void candidateOutOfSetIncreaseFailsGate() {
        RegressionGate.Result result = RegressionGate.evaluate(82.0, 82.0,
                Map.of("candidateOutOfSetRate", 0.0),
                Map.of("candidateOutOfSetRate", 0.1));

        assertFalse(result.passed());
        assertEquals(0.1, result.metricDeltas().get("candidateOutOfSetRate"));
    }

    @Test
    void toolCallIncreaseIsObservedButDoesNotFailGate() {
        RegressionGate.Result result = RegressionGate.evaluate(82.0, 82.0,
                Map.of("reactToolCallCount", 2.0),
                Map.of("reactToolCallCount", 4.0));

        assertTrue(result.passed());
        assertEquals(2.0, result.metricDeltas().get("reactToolCallCount"));
    }
}

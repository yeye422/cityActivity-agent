package com.city.service.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactReleaseGateTest {

    @Test
    void shouldPassHealthyReactMetrics() {
        ReactReleaseGate.Result result = ReactReleaseGate.evaluate(Map.of(
                "reactRouteCoverage", 0.9,
                "recommendationReactSuccessRate", 0.9,
                "planningReactSuccessRate", 0.8,
                "reactFallbackRate", 0.1,
                "evidenceViolationRate", 0.0
        ));

        assertTrue(result.passed());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void shouldFailWhenReactWasNotActuallyEnabled() {
        ReactReleaseGate.Result result = ReactReleaseGate.evaluate(Map.of(
                "reactRouteCoverage", 0.0
        ));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("recommendationReactSuccessRate")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("planningReactSuccessRate")));
    }

    @Test
    void shouldFailFallbackAndEvidenceViolationsEvenWithoutBaseline() {
        ReactReleaseGate.Result result = ReactReleaseGate.evaluate(Map.of(
                "reactRouteCoverage", 1.0,
                "recommendationReactSuccessRate", 0.7,
                "planningReactSuccessRate", 0.9,
                "reactFallbackRate", 0.3,
                "evidenceViolationRate", 0.1
        ));

        assertFalse(result.passed());
        assertTrue(result.failures().size() >= 3);
    }
}

package com.city.service.evaluation;

import com.city.exception.CityException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActEvaluationProfileTest {

    @Test
    void reactEvalProfileShouldEnableRecommendationAndPlanningReactRoutes() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "react-eval",
                new ClassPathResource("application-react-eval.yml")
        );
        PropertySource<?> source = sources.getFirst();

        assertEquals(Boolean.TRUE, source.getProperty("city.agent.recommendation-react.enabled"));
        assertEquals(Boolean.TRUE, source.getProperty("city.agent.planning-react.enabled"));
    }

    @Test
    void reactSuiteShouldUseIndependentResourceAndVersion() throws Exception {
        assertEquals("evaluation/city-dialogue-eval-set.json",
                RegressionEvaluationService.resolveEvalSetResource(null));
        assertEquals("evaluation/city-dialogue-eval-set.json",
                RegressionEvaluationService.resolveEvalSetResource("default"));
        assertEquals("evaluation/city-react-eval-set.json",
                RegressionEvaluationService.resolveEvalSetResource("ReAct"));
        assertThrows(CityException.class,
                () -> RegressionEvaluationService.resolveEvalSetResource("../../application"));

        try (InputStream input = new ClassPathResource(
                RegressionEvaluationService.resolveEvalSetResource("react")).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(input);
            assertEquals("react-v1", root.path("version").asText());
            assertTrue(root.path("cases").isArray());
            assertTrue(root.path("cases").size() >= 8);
        }
    }

    @Test
    void reactSuiteShouldRequireBothFeatureFlagsBeforeExecutingCases() {
        String reactResource = RegressionEvaluationService.resolveEvalSetResource("react");
        String defaultResource = RegressionEvaluationService.resolveEvalSetResource("default");

        assertDoesNotThrow(() -> RegressionEvaluationService.validateSuiteRuntime(
                defaultResource, false, false));
        assertDoesNotThrow(() -> RegressionEvaluationService.validateSuiteRuntime(
                reactResource, true, true));
        assertThrows(CityException.class, () -> RegressionEvaluationService.validateSuiteRuntime(
                reactResource, true, false));
        assertThrows(CityException.class, () -> RegressionEvaluationService.validateSuiteRuntime(
                reactResource, false, true));
        assertThrows(CityException.class, () -> RegressionEvaluationService.validateSuiteRuntime(
                reactResource, false, false));
    }
}

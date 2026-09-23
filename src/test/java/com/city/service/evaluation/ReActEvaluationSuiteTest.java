package com.city.service.evaluation;

import com.city.exception.CityException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReActEvaluationSuiteTest {

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
}

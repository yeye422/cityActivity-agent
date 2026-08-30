package com.city.service.intent;

import com.city.enums.ConstraintOperationType;
import com.city.model.ConstraintOperation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentAgentServiceOperationTest {

    private final IntentAgentService service = new IntentAgentService(
            null, null, null, null, "qwen-max");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void invalidNonClearOperationShouldBeDroppedAfterDictionarySanitization() throws Exception {
        JsonNode operations = objectMapper.readTree("""
                [
                  {"field":"activityType","op":"SET","values":["室内活动"],"raw":"室内活动"}
                ]
                """);
        Map<String, List<String>> options = Map.of(
                "activityType", List.of("电影", "展览", "演出", "桌游", "运动", "探店"));

        List<ConstraintOperation> result = ReflectionTestUtils.invokeMethod(
                service, "parseOperations", operations, options);

        assertTrue(result.isEmpty());
    }

    @Test
    void clearOperationShouldRemainValidWithEmptyValues() throws Exception {
        JsonNode operations = objectMapper.readTree("""
                [
                  {"field":"budget","op":"CLEAR","values":[],"raw":"预算不限"}
                ]
                """);
        Map<String, List<String>> options = Map.of(
                "budget", List.of("免费", "100元内", "200元内", "300元内"));

        List<ConstraintOperation> result = ReflectionTestUtils.invokeMethod(
                service, "parseOperations", operations, options);

        assertEquals(1, result.size());
        assertEquals("budget", result.getFirst().field());
        assertEquals(ConstraintOperationType.CLEAR, result.getFirst().op());
        assertTrue(result.getFirst().values().isEmpty());
    }

    @Test
    void fallbackShouldCreateClearOperationForExplicitUnlimitedBudget() {
        Map<String, List<String>> options = Map.of(
                "budget", List.of("免费", "100元内", "200元内", "300元内"));

        List<ConstraintOperation> result = ReflectionTestUtils.invokeMethod(
                service, "fallbackOperations", "预算不限", options);

        assertEquals(1, result.size());
        assertEquals("budget", result.getFirst().field());
        assertEquals(ConstraintOperationType.CLEAR, result.getFirst().op());
    }

    @Test
    void fallbackShouldCreateRemoveOperationForExplicitNegativePreference() {
        Map<String, List<String>> options = Map.of(
                "duration", List.of("户外", "室内", "半天", "全天"));

        List<ConstraintOperation> result = ReflectionTestUtils.invokeMethod(
                service, "fallbackOperations", "不要户外", options);

        assertEquals(1, result.size());
        assertEquals("duration", result.getFirst().field());
        assertEquals(ConstraintOperationType.REMOVE, result.getFirst().op());
        assertEquals(List.of("户外"), result.getFirst().values());
    }
}

package com.city.service.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EvaluationSetFingerprintTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void caseOrderDoesNotChangeFingerprint() throws Exception {
        JsonNode first = objectMapper.readTree("{\"id\":\"a\",\"message\":\"A\"}");
        JsonNode second = objectMapper.readTree("{\"id\":\"b\",\"message\":\"B\"}");

        String left = EvaluationSetFingerprint.sha256(objectMapper, List.of(first, second));
        String right = EvaluationSetFingerprint.sha256(objectMapper, List.of(second, first));

        assertEquals(left, right);
    }

    @Test
    void caseContentChangeProducesNewFingerprint() throws Exception {
        JsonNode before = objectMapper.readTree("{\"id\":\"a\",\"message\":\"A\"}");
        JsonNode after = objectMapper.readTree("{\"id\":\"a\",\"message\":\"changed\"}");

        assertNotEquals(
                EvaluationSetFingerprint.sha256(objectMapper, List.of(before)),
                EvaluationSetFingerprint.sha256(objectMapper, List.of(after))
        );
    }
}

package com.city.agent;

import io.agentscope.core.tool.ToolValidator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the packaged AgentScope tool-validation runtime against dependency conflicts.
 *
 * <p>AgentScope 1.0.11 could compile successfully while failing only when the first tool call
 * initialized networknt JSON Schema validation. This test deliberately crosses that runtime
 * boundary so CI catches incompatible Jackson/schema-validator dependency graphs before release.</p>
 */
class AgentScopeToolValidatorRuntimeTest {

    @Test
    void shouldValidateSimpleToolInputWithoutRuntimeLinkageErrors() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string")
                ),
                "required", java.util.List.of("query"),
                "additionalProperties", false
        );

        String validationError = ToolValidator.validateInput(
                "{\"query\":\"上海周末活动\"}",
                schema
        );

        assertNull(validationError);
    }
}

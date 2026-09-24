package com.city.agent;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.PlanCandidate;
import com.city.model.SlotBundle;
import com.city.model.agent.PlanValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.ToolValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the packaged AgentScope tool-validation runtime against dependency conflicts.
 *
 * <p>AgentScope 2.x 与宿主 Spring Boot 共享 Jackson/Reactor 依赖图。该测试刻意跨过
 * networknt JSON Schema 的真实运行时边界，使 CI 能在发布前发现二进制依赖冲突。</p>
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
    @Test
    void shouldSerializePlanValidationResultReturnedByTool() throws Exception {
        ActivityItem activity = new ActivityItem(
                1L,
                SourceMode.PUBLIC,
                null,
                "Museum",
                SlotBundle.empty(),
                null,
                null,
                null,
                null,
                90,
                0.9
        );
        PlanValidationResult result = PlanValidationResult.valid(new PlanCandidate(
                List.of(new PlanCandidate.Item("morning", activity, null)),
                BigDecimal.ZERO
        ));

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(result));

        JsonNode serializedActivity = json.path("acceptedPlan").path("items").get(0).path("activity");
        assertEquals(1L, serializedActivity.path("id").asLong());
        assertEquals("Museum", serializedActivity.path("name").asText());
        assertEquals("PUBLIC", serializedActivity.path("sourceType").asText());
    }

}

package com.city.service.intent;

import com.city.enums.PreferencePolarity;
import com.city.model.MemoryMutationProposal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentAgentServiceMemoryProposalTest {

    private final IntentAgentService service = new IntentAgentService(null, null, null, null, "qwen-max");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseOnlyDictionaryBackedMemoryProposal() throws Exception {
        JsonNode node = objectMapper.readTree("""
                [
                  {"slotName":"style","slotValue":"安静","polarity":"PREFER","explicitLongTerm":true,"raw":"以后都喜欢安静"},
                  {"slotName":"style","slotValue":"模型自造标签","polarity":"PREFER","explicitLongTerm":true,"raw":"以后"}
                ]
                """);

        List<MemoryMutationProposal> result = ReflectionTestUtils.invokeMethod(
                service, "parseMemoryProposals", node, Map.of("style", List.of("安静", "热闹")));

        assertEquals(1, result.size());
        assertEquals("style", result.getFirst().slotName());
        assertEquals(PreferencePolarity.PREFER, result.getFirst().polarity());
        assertTrue(result.getFirst().explicitLongTerm());
    }
}

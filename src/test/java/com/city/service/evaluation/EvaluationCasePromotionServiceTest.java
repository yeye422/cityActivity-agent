package com.city.service.evaluation;

import com.city.exception.CityException;
import com.city.mapper.EvaluationCaseMapper;
import com.city.model.EvaluationCaseRow;
import com.city.model.PromoteEvaluationCaseRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EvaluationCasePromotionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldResolveDefaultAndReactVersionsFromTheirOwnResources() {
        EvaluationCasePromotionService service =
                new EvaluationCasePromotionService(mock(EvaluationCaseMapper.class), objectMapper);

        assertEquals("v2", service.resolveEvalSetVersion(null));
        assertEquals("v2", service.resolveEvalSetVersion("default"));
        assertEquals("react-v1", service.resolveEvalSetVersion("react"));
        assertThrows(CityException.class, () -> service.resolveEvalSetVersion("unknown"));
    }

    @Test
    void shouldPersistReactBadCaseIntoReactVersion() {
        EvaluationCaseMapper mapper = mock(EvaluationCaseMapper.class);
        EvaluationCasePromotionService service = new EvaluationCasePromotionService(mapper, objectMapper);
        ObjectNode definition = objectMapper.createObjectNode()
                .put("id", "react_bad_001")
                .put("message", "周末在西安找个互动性强的活动");

        service.promote(7L, new PromoteEvaluationCaseRequest("trace-1", definition, "react"));

        ArgumentCaptor<EvaluationCaseRow> captor = ArgumentCaptor.forClass(EvaluationCaseRow.class);
        verify(mapper).insert(captor.capture());
        EvaluationCaseRow row = captor.getValue();
        assertEquals(7L, row.getUserId());
        assertEquals("react_bad_001", row.getCaseId());
        assertEquals("react-v1", row.getEvalSetVersion());
        assertEquals("trace-1", row.getSourceTraceId());
        assertEquals(7L, row.getCreatedBy());
    }

    @Test
    void shouldRejectCaseWithoutConversationInput() {
        EvaluationCasePromotionService service =
                new EvaluationCasePromotionService(mock(EvaluationCaseMapper.class), objectMapper);
        ObjectNode definition = objectMapper.createObjectNode().put("id", "bad");

        assertThrows(CityException.class,
                () -> service.promote(1L,
                        new PromoteEvaluationCaseRequest("trace", definition, "react")));
    }
}

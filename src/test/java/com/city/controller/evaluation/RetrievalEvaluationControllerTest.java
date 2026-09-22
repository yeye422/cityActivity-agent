package com.city.controller.evaluation;

import com.city.model.RetrievalEvaluationRequest;
import com.city.service.evaluation.RetrievalBaselineEvaluationService;
import com.city.service.evaluation.StableRetrievalQualityEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalEvaluationControllerTest {

    @Test
    void shouldDelegateUserAndKToBaselineService() {
        RetrievalBaselineEvaluationService service = mock(RetrievalBaselineEvaluationService.class);
        RetrievalBaselineEvaluationService.Report expected = new RetrievalBaselineEvaluationService.Report(
                "retrieval-v1",
                "CURRENT_PIPELINE",
                7,
                new StableRetrievalQualityEvaluator.Summary(0, null, null, null),
                List.of()
        );
        when(service.run(9L, 7)).thenReturn(expected);
        RetrievalEvaluationController controller = new RetrievalEvaluationController(service);

        RetrievalBaselineEvaluationService.Report actual =
                controller.evaluate(9L, new RetrievalEvaluationRequest(7));

        assertSame(expected, actual);
        verify(service).run(9L, 7);
    }

    @Test
    void missingBodyShouldUseServiceDefaultK() {
        RetrievalBaselineEvaluationService service = mock(RetrievalBaselineEvaluationService.class);
        RetrievalEvaluationController controller = new RetrievalEvaluationController(service);

        controller.evaluate(9L, null);

        verify(service).run(9L, null);
    }
}

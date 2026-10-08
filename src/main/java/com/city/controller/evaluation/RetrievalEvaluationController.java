package com.city.controller.evaluation;

import com.city.constants.CityConstants;
import com.city.model.RetrievalEvaluationRequest;
import com.city.service.evaluation.RetrievalBaselineEvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前 RetrievalPipeline 的离线质量评测入口，不经过 Agent/LLM。 */
@RestController
@RequestMapping("/api/v1/city/evaluations")
public class RetrievalEvaluationController {
    private final RetrievalBaselineEvaluationService retrievalEvaluationService;

    public RetrievalEvaluationController(RetrievalBaselineEvaluationService retrievalEvaluationService) {
        this.retrievalEvaluationService = retrievalEvaluationService;
    }

    @PostMapping("/retrieval")
    public RetrievalBaselineEvaluationService.Report evaluate(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "999999") Long userId,
            @RequestBody(required = false) RetrievalEvaluationRequest request
    ) {
        return retrievalEvaluationService.run(userId, request == null ? null : request.k());
    }
}

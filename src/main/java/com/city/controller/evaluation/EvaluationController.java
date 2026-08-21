package com.city.controller.evaluation;

import com.city.constants.CityConstants;
import com.city.model.EvaluationReport;
import com.city.model.EvaluationRequest;
import com.city.service.evaluation.EvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/city/evaluations")
public class EvaluationController {
    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping
    public EvaluationReport evaluate(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestBody EvaluationRequest request
    ) {
        return evaluationService.evaluate(userId, request);
    }
}






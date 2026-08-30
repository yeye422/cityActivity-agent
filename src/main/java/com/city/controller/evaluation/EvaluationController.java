package com.city.controller.evaluation;

import com.city.constants.CityConstants;
import com.city.model.EvaluationReport;
import com.city.model.EvaluationRequest;
import com.city.model.RegressionEvaluationRequest;
import com.city.model.RegressionEvaluationReport;
import com.city.model.PromoteEvaluationCaseRequest;
import com.city.service.evaluation.EvaluationService;
import com.city.service.evaluation.RegressionEvaluationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/city/evaluations")
public class EvaluationController {
    private final EvaluationService evaluationService;
    private final RegressionEvaluationService regressionEvaluationService;

    public EvaluationController(EvaluationService evaluationService,
                                RegressionEvaluationService regressionEvaluationService) {
        this.evaluationService = evaluationService;
        this.regressionEvaluationService = regressionEvaluationService;
    }

    @PostMapping
    public EvaluationReport evaluate(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestBody EvaluationRequest request
    ) {
        return evaluationService.evaluate(userId, request);
    }

    /** 执行 classpath 固定评测集，仅评估本次运行生成的 Trace。 */
    @PostMapping("/regression")
    public RegressionEvaluationReport regression(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "999999") Long userId,
            @RequestBody(required = false) RegressionEvaluationRequest request
    ) {
        return regressionEvaluationService.run(userId, request);
    }

    @PostMapping("/cases/promote")
    public void promoteCase(@RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
                            @RequestBody PromoteEvaluationCaseRequest request) {
        regressionEvaluationService.promote(userId, request);
    }
}



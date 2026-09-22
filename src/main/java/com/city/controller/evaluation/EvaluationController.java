package com.city.controller.evaluation;

import com.city.constants.CityConstants;
import com.city.model.EvaluationReport;
import com.city.model.EvaluationRequest;
import com.city.model.EvaluationRunRow;
import com.city.model.PromoteBaselineRequest;
import com.city.model.PromoteEvaluationCaseRequest;
import com.city.model.RegressionEvaluationReport;
import com.city.model.RegressionEvaluationRequest;
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

    /** 执行固定评测集，仅评估本次运行生成的 Trace，并自动绑定系统/评测版本指纹。 */
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

    /** 将一次已通过的评测 Run 显式提升为同一评测集指纹下的 Baseline。 */
    @PostMapping("/regression/baseline")
    public EvaluationRunRow promoteBaseline(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "999999") Long userId,
            @RequestBody PromoteBaselineRequest request
    ) {
        return regressionEvaluationService.promoteBaseline(userId, request);
    }
}

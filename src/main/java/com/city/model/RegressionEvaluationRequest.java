package com.city.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;

/** 固定评测集回归请求。评测集从 classpath evaluation/city-dialogue-eval-set.json 读取。 */
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class RegressionEvaluationRequest {
    private Boolean includeLlmJudge;
    private Integer limit;
}

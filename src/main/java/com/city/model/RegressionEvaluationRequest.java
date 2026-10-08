package com.city.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;

/** 固定评测集回归请求。suite=default 使用通用集，suite=react 使用 ReAct 专项集。 */
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class RegressionEvaluationRequest {
    private Boolean includeLlmJudge;
    private Integer limit;
    /** default / react；不接受任意 classpath，避免评测资源路径由请求直接控制。 */
    private String suite;
}

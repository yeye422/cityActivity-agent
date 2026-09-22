package com.city.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 将高价值 BadCase 晋级到固定评测集。
 * suite 仅支持 default / react；为空时保持旧行为写入 default。
 */
public record PromoteEvaluationCaseRequest(String traceId, JsonNode caseDefinition, String suite) {
    /** 向后兼容已有 Java 调用。 */
    public PromoteEvaluationCaseRequest(String traceId, JsonNode caseDefinition) {
        this(traceId, caseDefinition, null);
    }
}

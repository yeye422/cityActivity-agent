package com.city.model;

/** 时间解析链路的统一结果，供 Orchestrator 决定更新状态、保持不变或发起澄清。 */
public record TimeResolutionResult(
        Status status,
        TimeConstraint timeConstraint,
        String raw
) {
    public enum Status {
        LLM_SUCCESS,
        JAVA_FALLBACK,
        CLEAR,
        UNCHANGED,
        CLARIFY
    }

    public boolean shouldUpdateState() {
        return status == Status.LLM_SUCCESS || status == Status.JAVA_FALLBACK || status == Status.CLEAR;
    }

    public boolean needsClarification() {
        return status == Status.CLARIFY;
    }
}

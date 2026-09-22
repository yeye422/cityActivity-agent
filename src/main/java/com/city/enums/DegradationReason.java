package com.city.enums;

/** 推荐链路发生降级时使用的稳定原因，不把自由文本当成统计维度。 */
public enum DegradationReason {
    NONE,
    MODEL_FALLBACK,
    LOOP_DETECTED,
    CALL_BUDGET_EXCEEDED,
    CIRCUIT_OPEN,
    TOOL_TIMEOUT,
    MEMORY_UNAVAILABLE,
    SEMANTIC_RECALL_UNAVAILABLE,
    PERSONAL_TO_PUBLIC,
    NO_CANDIDATE,
    RELAXATION_REQUIRED
}

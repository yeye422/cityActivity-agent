package com.city.enums;

/**
 * Guard 对回复风险的分级结果。
 */
public enum RiskLevel {
    /** 普通活动推荐风险低，可以继续输出。 */
    LOW,

    /** 存在深夜独行、偏远地点、极端天气等高风险，必须保守拦截，不可降级放行。 */
    HIGH
}
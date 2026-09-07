package com.city.enums;

/**
 * 城市活动助手的一轮用户业务意图。
 *
 * <p>Intent 只描述需要进入哪个业务分支；澄清是 Orchestrator 根据执行前置条件产生的状态机动作，
 * 安全风险则由 RiskGuardService 统一处理，都不再作为 Intent 枚举值。</p>
 */
public enum Intent {
    /** 用户正在请求一个或多个活动推荐，例如“周六想看展览”。 */
    MEAL_RECOMMENDATION,

    /** 用户基于已有推荐修改条件或要求换一批。 */
    MEAL_ADJUST,

    /** 用户要求半天或一天的多时段活动规划。 */
    ACTIVITY_PLAN,

    /** 用户输入不属于推荐、调整或行程规划业务。 */
    OTHER
}

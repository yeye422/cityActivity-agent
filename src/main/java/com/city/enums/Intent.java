package com.city.enums;

/**
 * 城市活动助手的一轮用户输入意图。
 * 这个枚举是 Orchestrator 状态机的分支依据。
 */
public enum Intent {
    /** 用户正在请求活动推荐，例如”周六想看展览”。 */
    MEAL_RECOMMENDATION,

    /** 用户有活动意向，但当前信息不足，需要先追问关键槽位。 */
    CLARIFY_NEEDED,

    /** 用户基于上一轮推荐要求换一批、降低预算或改成室内。 */
    MEAL_ADJUST,

    /** 用户要求半天或一天的多时段活动规划。 */
    ACTIVITY_PLAN,

    /** 用户问题涉及深夜独行、偏远地点、极端天气等安全风险。 */
    HEALTH_RISK,

    /** 用户输入与城市活动无关内容。 */
    OTHER
}
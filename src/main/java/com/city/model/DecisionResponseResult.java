package com.city.model;

/**
 * Agent 决策完成后的统一结构化响应结果。
 *
 * <p>RecommendationAgent / PlanningAgent 已经负责决策，ResponseGenerator 只负责把经过验证的
 * 结构化结果转换成前端响应。该 DTO 独立于旧 ResponseAgent，便于彻底移除第二次 LLM 响应链。</p>
 */
public record DecisionResponseResult(
        RecommendResult recommend,
        ResponseResult response
) {
    public DecisionResponseResult {
        recommend = recommend == null ? RecommendResult.empty() : recommend;
        response = response == null ? ResponseResult.textOnly("") : response;
    }
}

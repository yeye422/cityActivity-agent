package com.city.model.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * RecommendationAgent 的结构化最终决策。
 *
 * <p>该对象只表达“从已检索候选中最终选什么以及为什么”，不携带数据库实体，也不允许
 * Agent 修改硬约束。最终进入业务响应前必须经过 RecommendationDecisionValidator。</p>
 */
@Data
@Accessors(fluent = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class RecommendationDecision {
    /** 最终选中的活动 ID，必须全部来自本轮 RetrievalTool 暴露的候选。 */
    private List<Long> selectedActivityIds;

    /** 对最终候选的软目标匹配解释。 */
    private List<CandidateAssessment> assessments;

    /** Agent 是否认为当前候选池已经足以支持最终推荐。 */
    private boolean candidatePoolSufficient;

    /** 面向后续 ResponseGenerator 的简短决策摘要，不应包含候选之外的新事实。 */
    private String decisionSummary;

    /** Agent 对本次软目标取舍的自评置信度，约定为 0~1。 */
    private double confidence;

    @Data
    @Accessors(fluent = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class CandidateAssessment {
        private Long activityId;
        /** HIGH / MEDIUM / LOW 等软目标匹配级别。 */
        private String goalFit;
        /** 只能依据 Tool 返回的候选字段解释，不得补充未知价格、地点、场次等事实。 */
        private String reason;
    }
}

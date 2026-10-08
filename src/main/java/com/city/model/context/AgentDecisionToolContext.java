package com.city.model.context;

import com.city.model.ActivityItem;
import com.city.service.evidence.CandidateEvidenceRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Recommendation / Planning 共享的模型不可见 Tool 上下文。
 *
 * <p>Agent 只能通过 Tool 参数表达软意图；用户身份、硬约束、当前 Run Evidence
 * 和已经暴露给模型的候选均由服务器注入。</p>
 */
public record AgentDecisionToolContext(
        VerifiedRequestContext verifiedRequestContext,
        CandidateEvidenceRegistry recommendationEvidenceRegistry,
        PlanningToolContext planningToolContext
) {
    public AgentDecisionToolContext {
        verifiedRequestContext = Objects.requireNonNull(verifiedRequestContext, "verifiedRequestContext");
        if (recommendationEvidenceRegistry == null && planningToolContext == null) {
            throw new IllegalArgumentException("必须提供 Recommendation 或 Planning Evidence 上下文");
        }
        if (recommendationEvidenceRegistry != null && planningToolContext != null) {
            throw new IllegalArgumentException("Recommendation 与 Planning Evidence 不能同时存在");
        }
    }

    public static AgentDecisionToolContext recommendation(
            VerifiedRequestContext verified,
            CandidateEvidenceRegistry evidenceRegistry
    ) {
        return new AgentDecisionToolContext(
                verified,
                Objects.requireNonNull(evidenceRegistry, "evidenceRegistry"),
                null
        );
    }

    public static AgentDecisionToolContext planning(PlanningToolContext planningContext) {
        Objects.requireNonNull(planningContext, "planningContext");
        return new AgentDecisionToolContext(
                planningContext.verifiedRequestContext(),
                null,
                planningContext
        );
    }

    public boolean planning() {
        return planningToolContext != null;
    }

    public Set<Long> exposedActivityIds() {
        if (recommendationEvidenceRegistry != null) {
            return recommendationEvidenceRegistry.exposedActivityIds();
        }
        return Set.copyOf(planningToolContext.evidenceRegistry().exposedActivityIds());
    }

    public List<ActivityItem> resolveActivities(List<Long> activityIds) {
        if (recommendationEvidenceRegistry != null) {
            return recommendationEvidenceRegistry.evidenceStore().resolveActivities(activityIds);
        }
        return planningToolContext.evidenceRegistry().evidenceStore().resolveActivities(activityIds);
    }
}

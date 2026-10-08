package com.city.service.recommend;

import com.city.model.agent.RecommendationDecision;
import com.city.service.evidence.CandidateEvidenceRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** RecommendationAgent 输出的确定性业务校验。 */
@Component
public class RecommendationDecisionValidator {

    public RecommendationDecision validate(RecommendationDecision decision,
                                           CandidateEvidenceRegistry evidenceRegistry) {
        Objects.requireNonNull(decision, "RecommendationDecision 不能为空");
        Objects.requireNonNull(evidenceRegistry, "CandidateEvidenceRegistry 不能为空");

        List<Long> selected = decision.selectedActivityIds() == null
                ? List.of()
                : decision.selectedActivityIds().stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        if (selected.isEmpty()) {
            throw new IllegalStateException("RecommendationAgent 未选择任何候选活动");
        }
        if (!decision.candidatePoolSufficient()) {
            throw new IllegalStateException("RecommendationAgent 在候选不足时仍结束了决策");
        }
        if (!evidenceRegistry.allExposed(selected)) {
            Set<Long> invalid = new LinkedHashSet<>(selected);
            invalid.removeAll(evidenceRegistry.exposedActivityIds());
            throw new IllegalStateException("RecommendationAgent 输出了未检索候选: " + invalid);
        }

        List<RecommendationDecision.CandidateAssessment> assessments = decision.assessments() == null
                ? List.of()
                : decision.assessments().stream()
                        .filter(Objects::nonNull)
                        .toList();
        for (RecommendationDecision.CandidateAssessment assessment : assessments) {
            if (assessment.activityId() == null || !evidenceRegistry.wasExposed(assessment.activityId())) {
                throw new IllegalStateException("RecommendationAgent assessment 引用了未检索候选: "
                        + assessment.activityId());
            }
        }

        double confidence = Math.max(0.0, Math.min(1.0, decision.confidence()));
        String summary = decision.decisionSummary() == null ? "" : decision.decisionSummary().trim();
        return new RecommendationDecision(
                selected,
                assessments,
                true,
                summary,
                confidence
        );
    }
}

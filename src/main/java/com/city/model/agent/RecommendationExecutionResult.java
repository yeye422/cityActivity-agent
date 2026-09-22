package com.city.model.agent;

import com.city.model.ActivityItem;
import com.city.service.evidence.CandidateEvidenceRegistry;

import java.util.List;

/** RecommendationWorker 的强类型执行结果，供 Workflow/响应层直接消费。 */
public record RecommendationExecutionResult(
        RecommendationDecision decision,
        List<ActivityItem> selectedActivities,
        int retrievalCalls,
        List<CandidateEvidenceRegistry.RetrievalRound> retrievalRounds
) {
    public RecommendationExecutionResult {
        if (decision == null) {
            throw new IllegalArgumentException("decision 不能为空");
        }
        selectedActivities = selectedActivities == null ? List.of() : List.copyOf(selectedActivities);
        retrievalRounds = retrievalRounds == null ? List.of() : List.copyOf(retrievalRounds);
        if (selectedActivities.isEmpty()) {
            throw new IllegalArgumentException("selectedActivities 不能为空");
        }
    }
}

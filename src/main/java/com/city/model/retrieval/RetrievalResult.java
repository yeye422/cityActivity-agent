package com.city.model.retrieval;

import com.city.model.ActivityDiversityDecision;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankScore;

import java.util.List;

/** RetrievalPipeline 的统一输出，保留每一层结果便于 Trace、评测和后续 Agent Evidence。 */
public record RetrievalResult(
        List<ActivityItem> rawCandidates,
        List<ActivityItem> rankedCandidates,
        List<ActivityItem> finalCandidates,
        List<ActivityRankScore> rankScores,
        List<ActivityDiversityDecision> diversityDecisions
) {
    public RetrievalResult {
        rawCandidates = safe(rawCandidates);
        rankedCandidates = safe(rankedCandidates);
        finalCandidates = safe(finalCandidates);
        rankScores = rankScores == null ? List.of() : List.copyOf(rankScores);
        diversityDecisions = diversityDecisions == null ? List.of() : List.copyOf(diversityDecisions);
    }

    private static List<ActivityItem> safe(List<ActivityItem> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

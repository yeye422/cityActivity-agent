package com.city.service.evidence;

import com.city.model.tool.RetrievalToolResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单次 RecommendationAgent 执行期间的候选证据登记表。
 *
 * <p>RetrievalTool 每次把真正返回给模型的 activityId 登记到这里；最终 RecommendationDecision
 * 只能引用已登记 ID。该对象只存在于一次 Agent 执行中，不承担跨请求持久化职责。</p>
 */
public final class CandidateEvidenceRegistry {
    private final int maxRetrievalCalls;
    private final Set<Long> exposedActivityIds = new LinkedHashSet<>();
    private final List<RetrievalRound> rounds = new ArrayList<>();

    public CandidateEvidenceRegistry(int maxRetrievalCalls) {
        if (maxRetrievalCalls <= 0) {
            throw new IllegalArgumentException("maxRetrievalCalls 必须大于 0");
        }
        this.maxRetrievalCalls = maxRetrievalCalls;
    }

    /** 在真正执行检索前占用一次调用预算。 */
    public synchronized void beginRetrieval(String retrievalIntent) {
        if (rounds.size() >= maxRetrievalCalls) {
            throw new IllegalStateException("RecommendationAgent 已达到本轮检索调用上限: " + maxRetrievalCalls);
        }
        rounds.add(new RetrievalRound(normalize(retrievalIntent), List.of()));
    }

    /** 记录本轮 Tool 真正暴露给模型的候选 ID。 */
    public synchronized void recordResult(RetrievalToolResult result) {
        if (rounds.isEmpty()) {
            throw new IllegalStateException("必须先 beginRetrieval 再记录候选结果");
        }
        List<Long> ids = result == null || result.candidates() == null
                ? List.of()
                : result.candidates().stream()
                        .map(RetrievalToolResult.Candidate::activityId)
                        .filter(id -> id != null)
                        .distinct()
                        .toList();
        exposedActivityIds.addAll(ids);
        int last = rounds.size() - 1;
        RetrievalRound previous = rounds.get(last);
        rounds.set(last, new RetrievalRound(previous.retrievalIntent(), ids));
    }

    public synchronized boolean wasExposed(Long activityId) {
        return activityId != null && exposedActivityIds.contains(activityId);
    }

    public synchronized boolean allExposed(List<Long> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) return false;
        return activityIds.stream().allMatch(this::wasExposed);
    }

    public synchronized Set<Long> exposedActivityIds() {
        return Set.copyOf(exposedActivityIds);
    }

    public synchronized int retrievalCalls() {
        return rounds.size();
    }

    public synchronized List<RetrievalRound> rounds() {
        return List.copyOf(rounds);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record RetrievalRound(String retrievalIntent, List<Long> activityIds) {
        public RetrievalRound {
            retrievalIntent = normalize(retrievalIntent);
            activityIds = activityIds == null ? List.of() : List.copyOf(activityIds);
        }
    }
}

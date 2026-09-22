package com.city.service.evidence;

import com.city.model.ActivityItem;
import com.city.model.tool.RetrievalToolResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 单次 RecommendationAgent 执行期间的候选证据登记表。
 *
 * <p>RetrievalTool 每次把真正返回给模型的 activityId 登记到这里；对应已验证 ActivityItem
 * 统一写入 RunEvidenceStore。Registry 继续负责检索调用预算和“是否真实暴露给模型”的语义。</p>
 */
public final class CandidateEvidenceRegistry {
    private final int maxRetrievalCalls;
    private final RunEvidenceStore evidenceStore;
    private final Set<Long> exposedActivityIds = new LinkedHashSet<>();
    private final List<RetrievalRound> rounds = new ArrayList<>();

    public CandidateEvidenceRegistry(int maxRetrievalCalls) {
        this(maxRetrievalCalls, RunEvidenceStore.transientStore());
    }

    public CandidateEvidenceRegistry(int maxRetrievalCalls, RunEvidenceStore evidenceStore) {
        if (maxRetrievalCalls <= 0) {
            throw new IllegalArgumentException("maxRetrievalCalls 必须大于 0");
        }
        this.maxRetrievalCalls = maxRetrievalCalls;
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
    }

    /** 在真正执行检索前占用一次调用预算。 */
    public synchronized void beginRetrieval(String retrievalIntent) {
        if (rounds.size() >= maxRetrievalCalls) {
            throw new IllegalStateException("RecommendationAgent 已达到本轮检索调用上限: " + maxRetrievalCalls);
        }
        rounds.add(new RetrievalRound(normalize(retrievalIntent), List.of()));
    }

    /** 记录本轮 Tool 真正暴露给模型的候选和对应业务实体。 */
    public synchronized void recordResult(RetrievalToolResult result, List<ActivityItem> sourceCandidates) {
        if (rounds.isEmpty()) {
            throw new IllegalStateException("必须先 beginRetrieval 再记录候选结果");
        }
        List<Long> ids = result == null || result.candidates() == null
                ? List.of()
                : result.candidates().stream()
                        .map(RetrievalToolResult.Candidate::activityId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        exposedActivityIds.addAll(ids);

        if (sourceCandidates != null) {
            for (ActivityItem candidate : sourceCandidates) {
                if (candidate != null && candidate.id() != null && ids.contains(candidate.id())) {
                    evidenceStore.recordActivity(candidate);
                }
            }
        }

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

    /** 按 Agent 最终决策顺序恢复服务器验证过的活动实体。 */
    public synchronized List<ActivityItem> resolveSelected(List<Long> activityIds) {
        return evidenceStore.resolveActivities(activityIds);
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

    public RunEvidenceStore evidenceStore() {
        return evidenceStore;
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

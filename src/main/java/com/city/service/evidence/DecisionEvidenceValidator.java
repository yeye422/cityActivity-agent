package com.city.service.evidence;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.PlanCandidate;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * RecommendationDecision / PlanningDecision 返回业务层之前的统一事实门禁。
 *
 * <p>这里只验证“最终实体是否来自当前 Run 已登记的权威 Evidence”，不重复执行排序、预算或 Solver。</p>
 */
public final class DecisionEvidenceValidator {

    public List<ActivityItem> validateRecommendation(List<Long> selectedActivityIds,
                                                     RunEvidenceStore evidenceStore) {
        Objects.requireNonNull(evidenceStore, "evidenceStore");
        if (selectedActivityIds == null || selectedActivityIds.isEmpty()) {
            throw new IllegalStateException("推荐结果缺少 activityId");
        }

        Set<Long> invalid = new LinkedHashSet<>();
        for (Long activityId : selectedActivityIds) {
            if (!evidenceStore.hasActivity(activityId)) invalid.add(activityId);
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("推荐结果引用当前 Run 未登记 Activity Evidence: " + invalid);
        }

        List<ActivityItem> resolved = evidenceStore.resolveActivities(selectedActivityIds);
        if (resolved.size() != selectedActivityIds.size()) {
            throw new IllegalStateException("推荐结果无法完整恢复为当前 Run 的 Activity Evidence");
        }
        return resolved;
    }

    public PlanCandidate validatePlan(PlanCandidate plan,
                                      RunEvidenceStore evidenceStore) {
        Objects.requireNonNull(evidenceStore, "evidenceStore");
        if (plan == null || plan.items().isEmpty()) {
            throw new IllegalStateException("最终规划为空");
        }

        for (PlanCandidate.Item item : plan.items()) {
            if (item == null || item.activity() == null || item.activity().id() == null
                    || !evidenceStore.hasActivity(item.activity().id())) {
                throw new IllegalStateException("规划结果引用当前 Run 未登记 Activity Evidence: "
                        + (item == null || item.activity() == null ? null : item.activity().id()));
            }
            ActivitySessionResponse session = item.session();
            if (session != null && (session.sessionId() == null || !evidenceStore.hasSession(session.sessionId()))) {
                throw new IllegalStateException("规划结果引用当前 Run 未登记 Session Evidence: "
                        + session.sessionId());
            }
        }

        validateTravelEvidence(plan, evidenceStore);
        return plan;
    }

    private void validateTravelEvidence(PlanCandidate plan,
                                        RunEvidenceStore evidenceStore) {
        List<PlanCandidate.Item> ordered = plan.items().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.startAt() != null)
                .sorted(Comparator.comparing(PlanCandidate.Item::startAt))
                .toList();

        for (int i = 1; i < ordered.size(); i++) {
            ActivitySessionResponse previous = ordered.get(i - 1).session();
            ActivitySessionResponse next = ordered.get(i).session();
            if (previous == null || next == null) {
                continue;
            }
            if (previous.venueId() == null || next.venueId() == null
                    || previous.venueId().equals(next.venueId())) {
                continue;
            }
            if (!evidenceStore.hasTravelEvidence(previous.venueId(), next.venueId())) {
                throw new IllegalStateException(
                        "规划结果缺少当前 Run 的 Travel Evidence: "
                                + previous.venueId() + "->" + next.venueId()
                );
            }
        }
    }
}

package com.city.service.evidence;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.TravelTimeEvidence;
import com.city.model.context.PlanningHorizon;
import com.city.service.plan.ActivityPlanService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单次 PlanningAgent Run 的 run-scoped Evidence Registry。
 *
 * <p>Activity/Session 不再绑定固定 period。每次发现只记录真实检索 range，
 * 最终计划合法性由 activityId/sessionId + PlanningHorizon 校验。</p>
 */
public final class PlanningEvidenceRegistry {

    private static final int DEFAULT_MAX_DISCOVERY_CALLS = 4;
    private static final int DEFAULT_MAX_VALIDATION_CALLS = 8;
    private static final int DEFAULT_MAX_TRAVEL_CALLS = 6;

    private final int maxDiscoveryCalls;
    private final int maxValidationCalls;
    private final int maxTravelCalls;
    private final RunEvidenceStore evidenceStore;

    private final Map<Long, ActivityItem> activities = new LinkedHashMap<>();
    private final Map<Long, Map<Long, ActivitySessionResponse>> sessionsByActivityId = new LinkedHashMap<>();
    private final List<PlanningHorizon.Range> searchedRanges = new ArrayList<>();
    private int discoveryCalls;
    private int validationCalls;
    private int travelCalls;

    public PlanningEvidenceRegistry() {
        this(DEFAULT_MAX_DISCOVERY_CALLS, DEFAULT_MAX_VALIDATION_CALLS, DEFAULT_MAX_TRAVEL_CALLS,
                RunEvidenceStore.transientStore());
    }

    public PlanningEvidenceRegistry(RunEvidenceStore evidenceStore) {
        this(DEFAULT_MAX_DISCOVERY_CALLS, DEFAULT_MAX_VALIDATION_CALLS, DEFAULT_MAX_TRAVEL_CALLS, evidenceStore);
    }

    public PlanningEvidenceRegistry(int maxDiscoveryCalls, int maxValidationCalls) {
        this(maxDiscoveryCalls, maxValidationCalls, DEFAULT_MAX_TRAVEL_CALLS, RunEvidenceStore.transientStore());
    }

    public PlanningEvidenceRegistry(int maxDiscoveryCalls, int maxValidationCalls, int maxTravelCalls) {
        this(maxDiscoveryCalls, maxValidationCalls, maxTravelCalls, RunEvidenceStore.transientStore());
    }

    public PlanningEvidenceRegistry(int maxDiscoveryCalls,
                                    int maxValidationCalls,
                                    int maxTravelCalls,
                                    RunEvidenceStore evidenceStore) {
        if (maxDiscoveryCalls <= 0 || maxValidationCalls <= 0 || maxTravelCalls <= 0) {
            throw new IllegalArgumentException("规划 Tool 调用预算必须大于 0");
        }
        this.maxDiscoveryCalls = maxDiscoveryCalls;
        this.maxValidationCalls = maxValidationCalls;
        this.maxTravelCalls = maxTravelCalls;
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
    }

    public synchronized void beginDiscovery() {
        if (discoveryCalls >= maxDiscoveryCalls) {
            throw new IllegalStateException("PlanningAgent 已达到候选发现调用上限: " + maxDiscoveryCalls);
        }
        discoveryCalls++;
    }

    public synchronized void beginValidation() {
        if (validationCalls >= maxValidationCalls) {
            throw new IllegalStateException("PlanningAgent 已达到方案校验安全预算上限: " + maxValidationCalls);
        }
        validationCalls++;
    }

    public synchronized void beginTravelLookup() {
        if (travelCalls >= maxTravelCalls) {
            throw new IllegalStateException("PlanningAgent 已达到路线查询调用上限: " + maxTravelCalls);
        }
        travelCalls++;
    }

    public synchronized void record(List<ActivityPlanService.CandidateBatch> batches) {
        if (batches == null) return;
        for (ActivityPlanService.CandidateBatch batch : batches) {
            if (batch == null) continue;
            if (!searchedRanges.contains(batch.range())) searchedRanges.add(batch.range());

            for (ActivityItem activity : batch.candidates()) {
                if (activity == null || activity.id() == null) continue;
                activities.putIfAbsent(activity.id(), activity);
                evidenceStore.recordActivity(activity);
            }

            batch.sessionsByActivityId().forEach((activityId, sessions) -> {
                if (activityId == null || sessions == null) return;
                Map<Long, ActivitySessionResponse> bySession =
                        sessionsByActivityId.computeIfAbsent(activityId, ignored -> new LinkedHashMap<>());
                for (ActivitySessionResponse session : sessions) {
                    if (session == null || session.sessionId() == null) continue;
                    bySession.putIfAbsent(session.sessionId(), session);
                    evidenceStore.recordSession(session);
                }
            });
        }
    }

    public synchronized void recordTravelEvidence(TravelTimeEvidence evidence) {
        evidenceStore.recordTravelEvidence(evidence);
    }

    public synchronized ActivityItem activity(Long activityId) {
        return activityId == null ? null : activities.get(activityId);
    }

    public synchronized ActivitySessionResponse session(Long activityId, Long sessionId) {
        if (activityId == null || sessionId == null) return null;
        return sessionsByActivityId.getOrDefault(activityId, Map.of()).get(sessionId);
    }

    public synchronized List<ActivitySessionResponse> sessionsForActivity(Long activityId) {
        if (activityId == null) return List.of();
        return List.copyOf(sessionsByActivityId.getOrDefault(activityId, Map.of()).values());
    }

    public synchronized List<ActivityItem> activities() {
        return List.copyOf(activities.values());
    }

    public synchronized Map<Long, List<ActivitySessionResponse>> sessionsByActivityId() {
        Map<Long, List<ActivitySessionResponse>> result = new LinkedHashMap<>();
        sessionsByActivityId.forEach((activityId, sessions) ->
                result.put(activityId, List.copyOf(sessions.values())));
        return Map.copyOf(result);
    }

    public synchronized List<PlanningHorizon.Range> searchedRanges() {
        return List.copyOf(searchedRanges);
    }

    public synchronized List<Long> exposedActivityIds() {
        return List.copyOf(activities.keySet());
    }

    public synchronized List<TravelTimeEvidence> travelTimeEvidence() {
        return evidenceStore.travelTimeEvidence();
    }

    public synchronized int discoveryCalls() {
        return discoveryCalls;
    }

    public synchronized int validationCalls() {
        return validationCalls;
    }

    public synchronized int travelCalls() {
        return travelCalls;
    }

    public RunEvidenceStore evidenceStore() {
        return evidenceStore;
    }
}

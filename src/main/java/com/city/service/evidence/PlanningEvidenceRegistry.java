package com.city.service.evidence;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.TravelTimeEvidence;
import com.city.service.plan.ActivityPlanService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单次 PlanningAgent 执行期间的窗口级证据登记表。
 *
 * <p>Registry 负责 period/activity/session 的窗口绑定关系和 Tool 调用预算；Activity、Session、Travel
 * 的权威事实统一写入 RunEvidenceStore，供最终 Decision Evidence 校验复用。</p>
 */
public final class PlanningEvidenceRegistry {

    private static final int DEFAULT_MAX_DISCOVERY_CALLS = 2;
    private static final int DEFAULT_MAX_VALIDATION_CALLS = 8;
    private static final int DEFAULT_MAX_TRAVEL_CALLS = 6;

    private final int maxDiscoveryCalls;
    private final int maxValidationCalls;
    private final int maxTravelCalls;
    private final RunEvidenceStore evidenceStore;
    private final Map<String, ActivityPlanService.PlannedActivity> windows = new LinkedHashMap<>();
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

    public synchronized void record(List<ActivityPlanService.PlannedActivity> plannedActivities) {
        if (plannedActivities == null) return;
        for (ActivityPlanService.PlannedActivity window : plannedActivities) {
            if (window == null || window.period() == null || window.period().isBlank()) continue;
            ActivityPlanService.PlannedActivity existing = windows.get(window.period());
            windows.put(window.period(), existing == null ? window : mergeWindow(existing, window));
            evidenceStore.recordActivities(window.candidates());
            if (window.sessionsByActivityId() != null) {
                window.sessionsByActivityId().values().stream()
                        .flatMap(List::stream)
                        .forEach(evidenceStore::recordSession);
            }
        }
    }

    private ActivityPlanService.PlannedActivity mergeWindow(
            ActivityPlanService.PlannedActivity existing,
            ActivityPlanService.PlannedActivity added
    ) {
        Map<Long, ActivityItem> candidates = new LinkedHashMap<>();
        for (ActivityItem item : existing.candidates()) {
            if (item != null && item.id() != null) candidates.putIfAbsent(item.id(), item);
        }
        for (ActivityItem item : added.candidates()) {
            if (item != null && item.id() != null) candidates.putIfAbsent(item.id(), item);
        }

        Map<Long, List<ActivitySessionResponse>> sessions = new LinkedHashMap<>();
        existing.sessionsByActivityId().forEach((activityId, values) ->
                sessions.put(activityId, values == null ? List.of() : List.copyOf(values)));
        added.sessionsByActivityId().forEach((activityId, values) -> {
            List<ActivitySessionResponse> merged = new ArrayList<>(
                    sessions.getOrDefault(activityId, List.of()));
            if (values != null) {
                for (ActivitySessionResponse session : values) {
                    if (session == null || session.sessionId() == null) continue;
                    boolean exists = merged.stream().anyMatch(current ->
                            current != null && session.sessionId().equals(current.sessionId()));
                    if (!exists) merged.add(session);
                }
            }
            sessions.put(activityId, List.copyOf(merged));
        });

        return new ActivityPlanService.PlannedActivity(
                existing.period(),
                existing.activity(),
                existing.querySlots(),
                List.copyOf(candidates.values()),
                Map.copyOf(sessions),
                existing.selectedSession()
        );
    }

    public synchronized void recordTravelEvidence(TravelTimeEvidence evidence) {
        evidenceStore.recordTravelEvidence(evidence);
    }

    public synchronized boolean hasPeriod(String period) {
        return period != null && windows.containsKey(period);
    }

    public synchronized ActivityItem activity(String period, Long activityId) {
        if (period == null || activityId == null) return null;
        ActivityPlanService.PlannedActivity window = windows.get(period);
        if (window == null) return null;
        return window.candidates().stream()
                .filter(item -> item != null && activityId.equals(item.id()))
                .findFirst()
                .orElse(null);
    }

    public synchronized ActivitySessionResponse session(String period, Long activityId, Long sessionId) {
        if (period == null || activityId == null || sessionId == null) return null;
        ActivityPlanService.PlannedActivity window = windows.get(period);
        if (window == null) return null;
        return window.sessionsByActivityId().getOrDefault(activityId, List.of()).stream()
                .filter(session -> session != null && sessionId.equals(session.sessionId()))
                .findFirst()
                .orElse(null);
    }

    public synchronized List<ActivityPlanService.PlannedActivity> windows() {
        return List.copyOf(windows.values());
    }

    public synchronized List<String> periods() {
        return List.copyOf(windows.keySet());
    }

    public synchronized List<Long> exposedActivityIds() {
        List<Long> result = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity window : windows.values()) {
            for (ActivityItem item : window.candidates()) {
                if (item != null && item.id() != null && !result.contains(item.id())) {
                    result.add(item.id());
                }
            }
        }
        return List.copyOf(result);
    }

    public synchronized List<String> periodsForActivity(Long activityId) {
        if (activityId == null) return List.of();
        List<String> result = new ArrayList<>();
        windows.forEach((period, window) -> {
            boolean present = window != null && window.candidates().stream()
                    .anyMatch(item -> item != null && activityId.equals(item.id()));
            if (present) result.add(period);
        });
        return List.copyOf(result);
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

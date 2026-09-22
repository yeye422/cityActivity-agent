package com.city.service.evidence;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.TravelTimeEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 单次 Agent Run 内的统一权威事实存储。
 *
 * <p>只保存 Tool / Java 业务服务已经验证并真实暴露给 Agent 的 Activity、Session 和 Travel Evidence。
 * 不负责 Tool 调用预算、业务排序或 SessionState 持久化。</p>
 */
public final class RunEvidenceStore {

    private final String runId;
    private final Map<Long, ActivityItem> activities = new LinkedHashMap<>();
    private final Map<Long, ActivitySessionResponse> sessions = new LinkedHashMap<>();
    private final Map<RouteKey, TravelTimeEvidence> travelEvidence = new LinkedHashMap<>();

    public RunEvidenceStore(String runId) {
        this.runId = normalizeRunId(runId);
    }

    public static RunEvidenceStore transientStore() {
        return new RunEvidenceStore("transient_" + UUID.randomUUID().toString().replace("-", ""));
    }

    public String runId() {
        return runId;
    }

    public synchronized void recordActivity(ActivityItem activity) {
        if (activity == null || activity.id() == null) return;
        activities.put(activity.id(), activity);
    }

    public synchronized void recordActivities(List<ActivityItem> source) {
        if (source == null) return;
        source.forEach(this::recordActivity);
    }

    public synchronized void recordSession(ActivitySessionResponse session) {
        if (session == null || session.sessionId() == null) return;
        sessions.put(session.sessionId(), session);
    }

    public synchronized void recordSessions(List<ActivitySessionResponse> source) {
        if (source == null) return;
        source.forEach(this::recordSession);
    }

    public synchronized void recordTravelEvidence(TravelTimeEvidence evidence) {
        if (evidence == null) return;
        travelEvidence.put(new RouteKey(evidence.fromVenueId(), evidence.toVenueId()), evidence);
    }

    public synchronized boolean hasActivity(Long activityId) {
        return activityId != null && activities.containsKey(activityId);
    }

    public synchronized boolean hasSession(Long sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    public synchronized ActivityItem activity(Long activityId) {
        return activityId == null ? null : activities.get(activityId);
    }

    public synchronized ActivitySessionResponse session(Long sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public synchronized boolean hasTravelEvidence(Long fromVenueId, Long toVenueId) {
        return fromVenueId != null
                && toVenueId != null
                && travelEvidence.containsKey(new RouteKey(fromVenueId, toVenueId));
    }

    public synchronized List<ActivityItem> resolveActivities(List<Long> activityIds) {
        if (activityIds == null || activityIds.isEmpty()) return List.of();
        List<ActivityItem> result = new ArrayList<>();
        for (Long activityId : activityIds) {
            ActivityItem activity = activities.get(activityId);
            if (activity != null) result.add(activity);
        }
        return List.copyOf(result);
    }

    public synchronized List<TravelTimeEvidence> travelTimeEvidence() {
        return List.copyOf(travelEvidence.values());
    }

    public synchronized EvidenceSnapshot snapshot() {
        return new EvidenceSnapshot(
                runId,
                List.copyOf(activities.keySet()),
                List.copyOf(sessions.keySet()),
                List.copyOf(travelEvidence.values())
        );
    }

    private static String normalizeRunId(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty()
                ? "transient_" + UUID.randomUUID().toString().replace("-", "")
                : normalized;
    }

    private record RouteKey(Long fromVenueId, Long toVenueId) {
        private RouteKey {
            Objects.requireNonNull(fromVenueId, "fromVenueId");
            Objects.requireNonNull(toVenueId, "toVenueId");
        }
    }

    public record EvidenceSnapshot(
            String runId,
            List<Long> activityIds,
            List<Long> sessionIds,
            List<TravelTimeEvidence> travelEvidence
    ) {
        public EvidenceSnapshot {
            activityIds = activityIds == null ? List.of() : List.copyOf(activityIds);
            sessionIds = sessionIds == null ? List.of() : List.copyOf(sessionIds);
            travelEvidence = travelEvidence == null ? List.of() : List.copyOf(travelEvidence);
        }
    }
}

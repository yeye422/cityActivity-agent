package com.city.service.evidence;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.service.plan.ActivityPlanService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次 PlanningAgent 执行期间的窗口级活动/场次证据表。
 * Agent 最终只能引用这里登记过的 period + activityId + sessionId 组合。
 * 同时承担规划 Tool 的轻量调用预算，防止 ReAct 循环重复灌入相同上下文。
 */
public final class PlanningEvidenceRegistry {

    private static final int DEFAULT_MAX_DISCOVERY_CALLS = 1;
    private static final int DEFAULT_MAX_VALIDATION_CALLS = 3;

    private final int maxDiscoveryCalls;
    private final int maxValidationCalls;
    private final Map<String, ActivityPlanService.PlannedActivity> windows = new LinkedHashMap<>();
    private int discoveryCalls;
    private int validationCalls;

    public PlanningEvidenceRegistry() {
        this(DEFAULT_MAX_DISCOVERY_CALLS, DEFAULT_MAX_VALIDATION_CALLS);
    }

    public PlanningEvidenceRegistry(int maxDiscoveryCalls, int maxValidationCalls) {
        if (maxDiscoveryCalls <= 0 || maxValidationCalls <= 0) {
            throw new IllegalArgumentException("规划 Tool 调用预算必须大于 0");
        }
        this.maxDiscoveryCalls = maxDiscoveryCalls;
        this.maxValidationCalls = maxValidationCalls;
    }

    public synchronized void beginDiscovery() {
        if (discoveryCalls >= maxDiscoveryCalls) {
            throw new IllegalStateException("PlanningAgent 已达到候选发现调用上限: " + maxDiscoveryCalls);
        }
        discoveryCalls++;
    }

    public synchronized void beginValidation() {
        if (validationCalls >= maxValidationCalls) {
            throw new IllegalStateException("PlanningAgent 已达到方案校验调用上限: " + maxValidationCalls);
        }
        validationCalls++;
    }

    public synchronized void record(List<ActivityPlanService.PlannedActivity> plannedActivities) {
        if (plannedActivities == null) return;
        for (ActivityPlanService.PlannedActivity window : plannedActivities) {
            if (window == null || window.period() == null || window.period().isBlank()) continue;
            windows.put(window.period(), window);
        }
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

    public synchronized int discoveryCalls() {
        return discoveryCalls;
    }

    public synchronized int validationCalls() {
        return validationCalls;
    }
}

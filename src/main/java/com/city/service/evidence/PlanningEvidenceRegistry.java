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
 */
public final class PlanningEvidenceRegistry {

    private final Map<String, ActivityPlanService.PlannedActivity> windows = new LinkedHashMap<>();

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
}

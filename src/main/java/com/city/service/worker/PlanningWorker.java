package com.city.service.worker;

import com.city.enums.SourceMode;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanningResult;
import com.city.service.plan.ActivityPlanService;

import java.util.List;

/** 多窗口候选发现与证据收集 Worker，不负责写会话状态。 */
public final class PlanningWorker {
    private final ActivityPlanService activityPlanService;

    public PlanningWorker(ActivityPlanService activityPlanService) {
        this.activityPlanService = activityPlanService;
    }

    public List<String> resolveWindows(SlotBundle slots, TimeConstraint timeConstraint) {
        return activityPlanService.resolveActivityTimes(slots, timeConstraint);
    }

    public PlanningResult plan(SourceMode sourceMode,
                               Long userId,
                               SlotBundle slots,
                               SlotBundle excludedSlots,
                               List<String> windows,
                               TimeConstraint timeConstraint,
                               WeatherRecommendationContext weather) {
        return activityPlanService.planWithEvidence(
                sourceMode, userId, slots, excludedSlots, windows, timeConstraint, weather);
    }
}

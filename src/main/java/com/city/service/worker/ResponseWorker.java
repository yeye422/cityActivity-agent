package com.city.service.worker;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.service.plan.ActivityPlanService;
import com.city.service.plan.PlanResponseAgentService;
import com.city.service.recommend.RecommendResponseAgentService;

import java.util.List;

/** 受候选白名单约束的表达 Worker，不能自行检索或制造实体。 */
public final class ResponseWorker {
    private final RecommendResponseAgentService recommendResponseAgentService;
    private final PlanResponseAgentService planResponseAgentService;

    public ResponseWorker(RecommendResponseAgentService recommendResponseAgentService,
                          PlanResponseAgentService planResponseAgentService) {
        this.recommendResponseAgentService = recommendResponseAgentService;
        this.planResponseAgentService = planResponseAgentService;
    }

    public RecommendResponseAgentService.Result recommend(String sessionId,
                                                          String userInput,
                                                          SourceMode sourceMode,
                                                          SlotBundle slots,
                                                          List<ActivityItem> candidates,
                                                          WeatherRecommendationContext weather) {
        return recommendResponseAgentService.recommendAndRespond(
                sessionId, userInput, sourceMode, slots, candidates, weather);
    }

    public RecommendResponseAgentService.Result plan(String sessionId,
                                                     String userInput,
                                                     SourceMode sourceMode,
                                                     SlotBundle slots,
                                                     List<ActivityPlanService.PlannedActivity> plans,
                                                     WeatherRecommendationContext weather) {
        return planResponseAgentService.planAndRespond(
                sessionId, userInput, sourceMode, slots, plans, weather);
    }
}

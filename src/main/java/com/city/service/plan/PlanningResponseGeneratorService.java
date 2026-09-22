package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivitySessionResponse;
import com.city.model.PlanCandidate;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.service.recommend.RecommendResponseAgentService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PlanningAgent 与旧 PlanResponseAgentService 之间的过渡表达层。
 * 旧响应 Agent 只能看到已经通过最终 Solver 复核的 PlanCandidate。
 */
@Service
public class PlanningResponseGeneratorService {

    private final PlanResponseAgentService legacyResponseService;

    public PlanningResponseGeneratorService(PlanResponseAgentService legacyResponseService) {
        this.legacyResponseService = Objects.requireNonNull(legacyResponseService, "legacyResponseService");
    }

    public RecommendResponseAgentService.Result generate(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle slots,
            PlanningAgentExecutionResult execution,
            WeatherRecommendationContext weather
    ) {
        Objects.requireNonNull(execution, "execution");
        List<ActivityPlanService.PlannedActivity> plans = toResponsePlans(execution.acceptedPlan(), slots);
        return legacyResponseService.planAndRespond(
                sessionId,
                userInput,
                sourceMode,
                slots,
                plans,
                weather
        );
    }

    List<ActivityPlanService.PlannedActivity> toResponsePlans(PlanCandidate acceptedPlan, SlotBundle slots) {
        if (acceptedPlan == null || acceptedPlan.items().isEmpty()) return List.of();
        SlotBundle safeSlots = slots == null ? SlotBundle.empty() : slots;
        return acceptedPlan.items().stream()
                .map(item -> {
                    ActivitySessionResponse session = item.session();
                    Map<Long, List<ActivitySessionResponse>> sessions = session == null
                            ? Map.of()
                            : Map.of(item.activity().id(), List.of(session));
                    return new ActivityPlanService.PlannedActivity(
                            item.period(),
                            item.activity(),
                            safeSlots,
                            List.of(item.activity()),
                            sessions,
                            session
                    );
                })
                .toList();
    }
}

package com.city.service.orchestrator;

import com.city.enums.Intent;
import com.city.enums.SourceMode;
import com.city.model.ChatResponse;
import com.city.model.RecommendResult;
import com.city.model.ResponseResult;
import com.city.model.RiskGuardResult;
import com.city.model.SessionState;
import com.city.model.WeatherRecommendationContext;
import com.city.service.activity.ActivityDiversityService;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import com.city.service.activity.ActivityService;
import com.city.service.activity.RelaxationSearchService;
import com.city.service.clarify.ClarifyRuleService;
import com.city.service.intent.IntentAgentService;
import com.city.service.intent.IntentReviseService;
import com.city.service.plan.ActivityPlanService;
import com.city.service.plan.PlanResponseAgentService;
import com.city.service.plan.PlanningDecisionFacade;
import com.city.service.recommend.RecommendResponseAgentService;
import com.city.service.recommend.RecommendationDecisionFacade;
import com.city.service.risk.RiskGuardService;
import com.city.service.session.SessionService;
import com.city.service.session.SessionStateService;
import com.city.service.slot.SlotMutationService;
import com.city.service.slot.SlotOptionService;
import com.city.service.time.TimeResolutionService;
import com.city.service.trace.AgentTraceService;
import com.city.service.weather.WeatherRecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CityAgentSupervisorPlanningReactTest {

    @Test
    void shouldUsePlanningReactFacadeWithoutExecutingLegacyPlanningWhenFacadeSucceeds() {
        SessionService sessionService = mock(SessionService.class);
        SessionStateService sessionStateService = mock(SessionStateService.class);
        ActivityPlanService activityPlanService = mock(ActivityPlanService.class);
        WeatherRecommendationService weatherService = mock(WeatherRecommendationService.class);
        RiskGuardService riskGuardService = mock(RiskGuardService.class);
        AgentTraceService traceService = mock(AgentTraceService.class);
        PlanningDecisionFacade planningDecisionFacade = mock(PlanningDecisionFacade.class);

        SessionState state = SessionState.fresh("sess_plan_react", 9L, SourceMode.PUBLIC)
                .withIntent(Intent.ACTIVITY_PLAN);
        WeatherRecommendationContext weather = WeatherRecommendationContext.inactive();
        List<String> windows = List.of("周六 14:00-16:00", "周六 18:00-20:00");
        RecommendResponseAgentService.Result reactResult = new RecommendResponseAgentService.Result(
                RecommendResult.empty(),
                ResponseResult.textOnly("ReAct 规划结果")
        );

        when(weatherService.resolve(any(), any())).thenReturn(weather);
        when(planningDecisionFacade.tryPlan(
                eq("帮我安排周六下午和晚上"),
                eq("trace-plan-react"),
                eq(state),
                eq(windows),
                eq(weather),
                eq(List.of())
        )).thenReturn(Optional.of(reactResult));
        when(riskGuardService.check(anyString(), any())).thenReturn(RiskGuardResult.pass());

        CityAgentSupervisor supervisor = new CityAgentSupervisor(
                sessionService,
                sessionStateService,
                mock(IntentAgentService.class),
                mock(IntentReviseService.class),
                mock(SlotOptionService.class),
                mock(SlotMutationService.class),
                mock(ClarifyRuleService.class),
                mock(ActivitySearchService.class),
                mock(ActivityRankService.class),
                mock(ActivityDiversityService.class),
                mock(RecommendResponseAgentService.class),
                activityPlanService,
                mock(PlanResponseAgentService.class),
                mock(ActivityService.class),
                mock(RelaxationSearchService.class),
                weatherService,
                mock(TimeResolutionService.class),
                riskGuardService,
                traceService,
                mock(RecommendationDecisionFacade.class)
        );
        supervisor.setPlanningDecisionFacade(planningDecisionFacade);

        ChatResponse response = ReflectionTestUtils.invokeMethod(
                supervisor,
                "completePlan",
                "sess_plan_react",
                9L,
                "帮我安排周六下午和晚上",
                "trace-plan-react",
                state,
                windows,
                false
        );

        assertEquals("ReAct 规划结果", response.speechText());
        verifyNoInteractions(activityPlanService);
        verify(planningDecisionFacade).tryPlan(
                eq("帮我安排周六下午和晚上"),
                eq("trace-plan-react"),
                eq(state),
                eq(windows),
                eq(weather),
                eq(List.of())
        );
        verify(sessionStateService).save(any(SessionState.class));
        verify(sessionService).appendMessage(
                eq("sess_plan_react"),
                eq("assistant"),
                eq("ReAct 规划结果"),
                eq(Intent.ACTIVITY_PLAN.name()),
                eq("trace-plan-react")
        );
    }
}

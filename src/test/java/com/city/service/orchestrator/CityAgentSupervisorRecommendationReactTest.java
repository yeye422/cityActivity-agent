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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CityAgentSupervisorRecommendationReactTest {

    @Test
    void shouldUseReactFacadeWithoutExecutingLegacyRetrievalWhenFacadeSucceeds() {
        SessionService sessionService = mock(SessionService.class);
        SessionStateService sessionStateService = mock(SessionStateService.class);
        ActivitySearchService activitySearchService = mock(ActivitySearchService.class);
        WeatherRecommendationService weatherService = mock(WeatherRecommendationService.class);
        RiskGuardService riskGuardService = mock(RiskGuardService.class);
        AgentTraceService traceService = mock(AgentTraceService.class);
        RecommendationDecisionFacade decisionFacade = mock(RecommendationDecisionFacade.class);

        SessionState state = SessionState.fresh("sess_react", 7L, SourceMode.PUBLIC)
                .withIntent(Intent.ACTIVITY_RECOMMENDATION);
        WeatherRecommendationContext weather = WeatherRecommendationContext.inactive();
        RecommendResponseAgentService.Result reactResult = new RecommendResponseAgentService.Result(
                RecommendResult.empty(),
                ResponseResult.textOnly("ReAct 推荐结果")
        );

        when(weatherService.resolve(any(), any())).thenReturn(weather);
        when(decisionFacade.tryRecommend(eq("想找特别一点的约会活动"), eq("trace-react"), eq(state), eq(List.of()), eq(weather)))
                .thenReturn(Optional.of(reactResult));
        when(riskGuardService.check(anyString(), any())).thenReturn(RiskGuardResult.pass());

        CityAgentSupervisor supervisor = new CityAgentSupervisor(
                sessionService,
                sessionStateService,
                mock(IntentAgentService.class),
                mock(IntentReviseService.class),
                mock(SlotOptionService.class),
                mock(SlotMutationService.class),
                mock(ClarifyRuleService.class),
                activitySearchService,
                mock(ActivityRankService.class),
                mock(ActivityDiversityService.class),
                mock(RecommendResponseAgentService.class),
                mock(ActivityPlanService.class),
                mock(PlanResponseAgentService.class),
                mock(ActivityService.class),
                mock(RelaxationSearchService.class),
                weatherService,
                mock(TimeResolutionService.class),
                riskGuardService,
                traceService,
                decisionFacade
        );

        ChatResponse response = ReflectionTestUtils.invokeMethod(
                supervisor,
                "completeRecommendation",
                "sess_react",
                7L,
                "想找特别一点的约会活动",
                "trace-react",
                state,
                List.of(),
                false
        );

        assertEquals("ReAct 推荐结果", response.speechText());
        verify(activitySearchService, never()).search(any());
        verify(decisionFacade).tryRecommend(
                eq("想找特别一点的约会活动"), eq("trace-react"), eq(state), eq(List.of()), eq(weather));
        verify(sessionStateService).save(any(SessionState.class));
        verify(sessionService).appendMessage(
                eq("sess_react"), eq("assistant"), eq("ReAct 推荐结果"),
                eq(Intent.ACTIVITY_RECOMMENDATION.name()), eq("trace-react"));
    }
}

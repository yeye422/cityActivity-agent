package com.city.service.orchestrator;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.model.ChatResponse;
import com.city.model.SessionState;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CityAgentSupervisorChitchatStateTest {

    @Test
    void chitchatDuringClarificationShouldPreserveBusinessState() {
        SessionService sessionService = mock(SessionService.class);
        SessionStateService sessionStateService = mock(SessionStateService.class);
        AgentTraceService traceService = mock(AgentTraceService.class);
        CityAgentSupervisor service = service(sessionService, sessionStateService, traceService);

        SessionState state = SessionState.fresh("sess_plan", 1L, SourceMode.PUBLIC)
                .withIntent(Intent.ACTIVITY_PLAN)
                .withPhase(SessionPhase.CLARIFY)
                .withPendingClarifyField(ClarifyField.CITY);

        ChatResponse response = ReflectionTestUtils.invokeMethod(
                service, "handleChitchat", "sess_plan", "trace_chat", state);

        ArgumentCaptor<SessionState> savedCaptor = ArgumentCaptor.forClass(SessionState.class);
        verify(sessionStateService).save(savedCaptor.capture());
        SessionState saved = savedCaptor.getValue();

        assertEquals(Intent.ACTIVITY_PLAN, saved.currentIntent());
        assertEquals(SessionPhase.CLARIFY, saved.phase());
        assertEquals(ClarifyField.CITY, saved.pendingClarifyField());
        assertEquals("ANSWER", response.responseType());

        // 消息本身仍是闲聊语义，SessionState 则继续保存原业务任务。
        verify(sessionService).appendMessage(
                eq("sess_plan"), eq("assistant"), anyString(), eq(Intent.OTHER.name()), eq("trace_chat"));
    }

    private CityAgentSupervisor service(SessionService sessionService,
                                        SessionStateService sessionStateService,
                                        AgentTraceService traceService) {
        return new CityAgentSupervisor(
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
                mock(ActivityPlanService.class),
                mock(PlanResponseAgentService.class),
                mock(ActivityService.class),
                mock(RelaxationSearchService.class),
                mock(WeatherRecommendationService.class),
                mock(TimeResolutionService.class),
                mock(RiskGuardService.class),
                traceService,
                mock(RecommendationDecisionFacade.class)
        );
    }
}

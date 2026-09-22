package com.city.service.orchestrator;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.model.ChatResponse;
import com.city.model.ResponseResult;
import com.city.model.SessionState;
import com.city.service.activity.ActivityService;
import com.city.service.activity.RelaxationSearchService;
import com.city.service.clarify.ClarifyRuleService;
import com.city.service.recommend.RecommendationResponseGeneratorService;
import com.city.service.risk.RiskGuardService;
import com.city.service.session.SessionService;
import com.city.service.session.SessionStateService;
import com.city.service.slot.SlotOptionService;
import com.city.service.time.TimeResolutionService;
import com.city.service.trace.AgentTraceService;
import com.city.service.weather.WeatherRecommendationService;
import com.city.service.worker.ContextWorker;
import com.city.service.workflow.WorkflowRouter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CityAgentSupervisorChitchatStateTest {

    @Test
    void chitchatDuringClarificationShouldPreserveBusinessState() {
        DecisionCommitService commitService = mock(DecisionCommitService.class);
        CityAgentSupervisor supervisor = new CityAgentSupervisor(
                mock(SessionService.class),
                mock(SessionStateService.class),
                mock(SlotOptionService.class),
                mock(ActivityService.class),
                mock(ContextWorker.class),
                mock(TimeResolutionService.class),
                mock(RiskGuardService.class),
                mock(ClarifyRuleService.class),
                mock(WorkflowRouter.class),
                mock(CityFlowWorkflowExecutor.class),
                commitService,
                mock(RelaxationSearchService.class),
                mock(RecommendationResponseGeneratorService.class),
                mock(WeatherRecommendationService.class),
                mock(AgentTraceService.class));

        SessionState state = SessionState.fresh("sess_plan", 1L, SourceMode.PUBLIC)
                .withIntent(Intent.ACTIVITY_PLAN)
                .withPhase(SessionPhase.CLARIFY)
                .withPendingClarifyField(ClarifyField.CITY);
        ChatResponse expected = mock(ChatResponse.class);
        when(commitService.commitText(
                eq("trace_chat"), eq(state), eq(Intent.OTHER), any(ResponseResult.class), eq(true)))
                .thenReturn(expected);

        ChatResponse actual = ReflectionTestUtils.invokeMethod(
                supervisor, "handleChitchat", "trace_chat", state);

        assertSame(expected, actual);
        verify(commitService).commitText(
                eq("trace_chat"), eq(state), eq(Intent.OTHER), any(ResponseResult.class), eq(true));
    }
}

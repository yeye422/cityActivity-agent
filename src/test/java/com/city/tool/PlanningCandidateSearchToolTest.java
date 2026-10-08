package com.city.tool;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanNotebook;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.PlanningHorizon;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.SemanticContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.evidence.PlanningEvidenceRegistry;
import com.city.service.plan.ActivityPlanService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanningCandidateSearchToolTest {

    @Test
    void shouldSearchMissingRangeInsideHorizonAndMergeEvidence() {
        ActivityPlanService service = mock(ActivityPlanService.class);
        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        registry.beginDiscovery();
        PlanningHorizon.Range full = range(8, 23);
        registry.record(List.of(new ActivityPlanService.CandidateBatch(
                full, List.of(activity(101L, "美术展")), Map.of())));

        PlanningHorizon.Range missing = range(15, 18);
        ActivityItem expanded = activity(202L, "陶艺");
        when(service.discoverRange(
                any(), any(), any(), any(), eq(missing),
                any(), anyString(), anyList()
        )).thenReturn(new ActivityPlanService.CandidateBatch(
                missing, List.of(expanded), Map.of()));

        PlanningHorizon horizon = new PlanningHorizon(List.of(full));
        PlanningToolContext planning = new PlanningToolContext(
                verified(),
                horizon,
                registry,
                new PlanNotebook(horizon),
                null,
                List.of()
        );

        PlanningCandidateSearchTool tool = new PlanningCandidateSearchTool(service, null);
        tool.search(
                "2026-09-27T15:00:00",
                "2026-09-27T18:00:00",
                "情侣互动、轻松",
                AgentDecisionToolContext.planning(planning)
        );

        assertEquals(2, registry.discoveryCalls());
        assertNotNull(registry.activity(101L));
        assertNotNull(registry.activity(202L));
        assertTrue(registry.searchedRanges().contains(missing));
    }

    @Test
    void shouldRejectRangeOutsideServerHorizon() {
        PlanningHorizon horizon = new PlanningHorizon(List.of(range(8, 18)));
        PlanningToolContext planning = new PlanningToolContext(
                verified(), horizon, new PlanningEvidenceRegistry(),
                new PlanNotebook(horizon), null, List.of());

        assertThrows(com.city.exception.CityException.class, () ->
                new PlanningCandidateSearchTool(mock(ActivityPlanService.class), null).search(
                        "2026-09-27T19:00:00",
                        "2026-09-27T21:00:00",
                        "夜间活动",
                        AgentDecisionToolContext.planning(planning)
                ));
    }

    private PlanningHorizon.Range range(int startHour, int endHour) {
        return new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, startHour, 0),
                LocalDateTime.of(2026, 9, 27, endHour, 0));
    }

    private VerifiedRequestContext verified() {
        SessionState state = SessionState.fresh("session-search", 9L, SourceMode.PUBLIC);
        return VerifiedRequestContext.from(
                state, "trace-search", SemanticContext.empty(),
                WeatherRecommendationContext.inactive());
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 90, 0.5);
    }
}

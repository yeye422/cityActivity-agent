package com.city.tool;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanNotebook;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.SemanticContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.evidence.PlanningEvidenceRegistry;
import com.city.service.plan.ActivityPlanService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningCandidateExpansionToolTest {

    @Test
    void shouldExpandExistingPeriodAndMergeEvidence() {
        ActivityPlanService service = mock(ActivityPlanService.class);
        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        registry.beginDiscovery();
        ActivityItem initial = activity(101L, "美术展");
        registry.record(List.of(new ActivityPlanService.PlannedActivity(
                "AFTERNOON", null, SlotBundle.empty(), List.of(initial), Map.of(), null)));

        ActivityItem expanded = activity(202L, "陶艺");
        when(service.expandWindow(
                any(), any(), any(), any(), eq("AFTERNOON"),
                any(), any(), anyString(), anyList()
        )).thenReturn(new ActivityPlanService.PlannedActivity(
                "AFTERNOON", null, SlotBundle.empty(), List.of(expanded), Map.of(), null));

        PlanningToolContext planning = new PlanningToolContext(
                verified(),
                List.of("AFTERNOON"),
                registry,
                new PlanNotebook(List.of("AFTERNOON")),
                null,
                List.of()
        );
        PlanningCandidateExpansionTool tool = new PlanningCandidateExpansionTool(service, null);

        tool.expand(
                "AFTERNOON",
                "更互动的情侣体验",
                AgentDecisionToolContext.planning(planning)
        );

        assertEquals(2, registry.discoveryCalls());
        assertNotNull(registry.activity("AFTERNOON", 101L));
        assertNotNull(registry.activity("AFTERNOON", 202L));
    }

    private VerifiedRequestContext verified() {
        SessionState state = SessionState.fresh("session-expand", 9L, SourceMode.PUBLIC);
        return VerifiedRequestContext.from(
                state, "trace-expand", SemanticContext.empty(),
                WeatherRecommendationContext.inactive());
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 90, 0.5);
    }
}

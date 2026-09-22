package com.city.service.worker;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.AgentResult;
import com.city.model.agent.PlanningExecutionResult;
import com.city.model.agent.PlanningResult;
import com.city.service.plan.ActivityPlanService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningWorkerTest {

    @Test
    void shouldPassWindowCandidatesIntoDeterministicSolver() {
        ActivityPlanService planService = mock(ActivityPlanService.class);
        PlanningWorker worker = new PlanningWorker(planService);
        ActivityItem item = new ActivityItem(
                1L, SourceMode.PUBLIC, null, "展览", SlotBundle.empty(),
                null, null, null, null, 60, 1.0);
        ActivitySessionResponse session = new ActivitySessionResponse(
                101L, 1L, 10L, "展馆", "VENUE", "西安", "雁塔", "地址",
                LocalDateTime.of(2026, 9, 26, 14, 0),
                LocalDateTime.of(2026, 9, 26, 15, 0),
                new BigDecimal("80"), 10, "OPEN", null, null);
        ActivityPlanService.PlannedActivity planned = new ActivityPlanService.PlannedActivity(
                "14:00-16:00", null, SlotBundle.empty(), List.of(item), Map.of(1L, List.of(session)), null);
        PlanningResult planning = new PlanningResult(
                List.of(planned),
                new AgentResult(AgentResult.Status.COMPLETED, "ok", Set.of(), Set.of(), List.of(), List.of(), Map.of()));
        when(planService.planWithEvidence(any(), any(), any(), any(), any(), any(), any())).thenReturn(planning);

        PlanningExecutionResult result = worker.planAndSolve(
                SourceMode.PUBLIC, 1L,
                new SlotBundle(List.of(), List.of(), List.of(), List.of(), List.of("100元内"),
                        List.of(), List.of(), List.of(), List.of()),
                SlotBundle.empty(), List.of("14:00-16:00"), null,
                WeatherRecommendationContext.inactive());

        assertEquals(planning, result.planning());
        assertEquals(1, result.planCandidates().size());
        assertEquals(new BigDecimal("80"), result.planCandidates().getFirst().totalCost());
    }

    @Test
    void shouldOnlyCreateHardBudgetForUnambiguousValue() {
        PlanningWorker worker = new PlanningWorker(mock(ActivityPlanService.class));

        assertEquals(new BigDecimal("200"), worker.explicitMaxBudget(new SlotBundle(
                List.of(), List.of(), List.of(), List.of(), List.of("200元内"),
                List.of(), List.of(), List.of(), List.of())));
        assertEquals(BigDecimal.ZERO, worker.explicitMaxBudget(new SlotBundle(
                List.of(), List.of(), List.of(), List.of(), List.of("免费"),
                List.of(), List.of(), List.of(), List.of())));
        assertNull(worker.explicitMaxBudget(new SlotBundle(
                List.of(), List.of(), List.of(), List.of(), List.of("100元内", "200元内"),
                List.of(), List.of(), List.of(), List.of())));
    }
}

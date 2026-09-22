package com.city.service.worker;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.PlanCandidate;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningWorkerTest {

    @Test
    void shouldPassWindowCandidatesIntoDeterministicSolver() {
        ActivityPlanService planService = mock(ActivityPlanService.class);
        PlanningWorker worker = new PlanningWorker(planService);
        ActivityItem item = activity(1L, "展览");
        ActivitySessionResponse session = session(101L, 1L, 14, 15, "80");
        ActivityPlanService.PlannedActivity planned = new ActivityPlanService.PlannedActivity(
                "14:00-16:00", null, SlotBundle.empty(), List.of(item), Map.of(1L, List.of(session)), null);
        PlanningResult planning = planning(List.of(planned));
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
    void shouldLockResponseToSolverApprovedActivitiesAndSessions() {
        PlanningWorker worker = new PlanningWorker(mock(ActivityPlanService.class));
        ActivityItem first = activity(1L, "候选1");
        ActivityItem unapproved = activity(2L, "未入选候选");
        ActivityItem second = activity(3L, "候选3");
        ActivitySessionResponse firstSession = session(101L, 1L, 14, 15, "20");
        ActivitySessionResponse unapprovedSession = session(201L, 2L, 14, 15, "30");
        ActivitySessionResponse secondSession = session(301L, 3L, 18, 19, "40");

        ActivityPlanService.PlannedActivity afternoon = new ActivityPlanService.PlannedActivity(
                "14:00-16:00", null, SlotBundle.empty(), List.of(first, unapproved),
                Map.of(1L, List.of(firstSession), 2L, List.of(unapprovedSession)), null);
        ActivityPlanService.PlannedActivity evening = new ActivityPlanService.PlannedActivity(
                "18:00-20:00", null, SlotBundle.empty(), List.of(second),
                Map.of(3L, List.of(secondSession)), null);
        PlanningResult planning = planning(List.of(afternoon, evening));
        PlanCandidate approved = new PlanCandidate(
                List.of(new PlanCandidate.Item("14:00-16:00", first, firstSession)),
                new BigDecimal("20"));

        List<ActivityPlanService.PlannedActivity> responsePlans = worker.responsePlans(
                new PlanningExecutionResult(planning, List.of(approved)));

        assertEquals(2, responsePlans.size());
        assertEquals(List.of(1L), responsePlans.getFirst().candidates().stream().map(ActivityItem::id).toList());
        assertEquals(101L, responsePlans.getFirst().selectedSession().sessionId());
        assertTrue(responsePlans.get(1).candidates().isEmpty());
        assertNull(responsePlans.get(1).activity());
    }

    @Test
    void livePlanEntryShouldExposeOnlySolverApprovedCandidate() {
        ActivityPlanService planService = mock(ActivityPlanService.class);
        PlanningWorker worker = new PlanningWorker(planService);
        ActivityItem cheaper = activity(1L, "候选1");
        ActivityItem expensive = activity(2L, "候选2");
        ActivitySessionResponse cheaperSession = session(101L, 1L, 14, 15, "20");
        ActivitySessionResponse expensiveSession = session(201L, 2L, 14, 15, "30");
        ActivityPlanService.PlannedActivity window = new ActivityPlanService.PlannedActivity(
                "14:00-16:00", null, SlotBundle.empty(), List.of(cheaper, expensive),
                Map.of(1L, List.of(cheaperSession), 2L, List.of(expensiveSession)), null);
        when(planService.planWithEvidence(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(planning(List.of(window)));

        PlanningResult liveResult = worker.plan(
                SourceMode.PUBLIC, 1L, SlotBundle.empty(), SlotBundle.empty(),
                List.of("14:00-16:00"), null, WeatherRecommendationContext.inactive());

        assertEquals(1, liveResult.plans().size());
        assertEquals(List.of(1L), liveResult.plans().getFirst().candidates().stream().map(ActivityItem::id).toList());
        assertEquals(101L, liveResult.plans().getFirst().selectedSession().sessionId());
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

    private PlanningResult planning(List<ActivityPlanService.PlannedActivity> plans) {
        return new PlanningResult(
                plans,
                new AgentResult(AgentResult.Status.COMPLETED, "ok", Set.of(), Set.of(), List.of(), List.of(), Map.of()));
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 60, 1.0);
    }

    private ActivitySessionResponse session(Long sessionId,
                                            Long activityId,
                                            int startHour,
                                            int endHour,
                                            String price) {
        return new ActivitySessionResponse(
                sessionId, activityId, 10L + activityId, "场地", "VENUE", "西安", "雁塔", "地址",
                LocalDateTime.of(2026, 9, 26, startHour, 0),
                LocalDateTime.of(2026, 9, 26, endHour, 0),
                new BigDecimal(price), 10, "OPEN", null, null);
    }
}

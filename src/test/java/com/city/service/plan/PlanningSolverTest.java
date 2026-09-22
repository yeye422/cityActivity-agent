package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.PlanCandidate;
import com.city.model.SlotBundle;
import com.city.model.TravelTimeEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningSolverTest {

    private final PlanningSolver solver = new PlanningSolver();

    @Test
    void shouldDeduplicateActivitiesAcrossWindows() {
        ActivityItem first = activity(1L, "活动1");
        ActivityItem second = activity(2L, "活动2");

        ActivityPlanService.PlannedActivity w1 = window(
                "14:00-16:00", List.of(first),
                Map.of(1L, List.of(session(101L, 1L, 14, 15, "20", 10, "OPEN"))));
        ActivityPlanService.PlannedActivity w2 = window(
                "16:00-18:00", List.of(first, second),
                Map.of(
                        1L, List.of(session(102L, 1L, 16, 17, "20", 10, "OPEN")),
                        2L, List.of(session(201L, 2L, 16, 17, "30", 10, "OPEN"))));

        PlanCandidate plan = solver.solve(List.of(w1, w2)).getFirst();

        assertEquals(List.of(1L, 2L), plan.items().stream().map(item -> item.activity().id()).toList());
    }

    @Test
    void shouldRejectInvalidAndOverlappingSessions() {
        ActivityItem first = activity(1L, "活动1");
        ActivityItem closed = activity(2L, "已关闭");
        ActivityItem overlap = activity(3L, "时间冲突");
        ActivityItem valid = activity(4L, "合法活动");

        ActivityPlanService.PlannedActivity w1 = window(
                "14:00-16:00", List.of(first),
                Map.of(1L, List.of(session(101L, 1L, 14, 16, "20", 10, "OPEN"))));
        ActivityPlanService.PlannedActivity w2 = window(
                "16:00-18:00", List.of(closed, overlap, valid),
                Map.of(
                        2L, List.of(session(201L, 2L, 16, 17, "20", 10, "CLOSED")),
                        3L, List.of(session(301L, 3L, 15, 17, "20", 10, "OPEN")),
                        4L, List.of(session(401L, 4L, 16, 18, "20", 10, "OPEN"))));

        PlanCandidate plan = solver.solve(List.of(w1, w2)).getFirst();

        assertEquals(List.of(1L, 4L), plan.items().stream().map(item -> item.activity().id()).toList());
    }

    @Test
    void shouldRespectKnownSessionBudget() {
        ActivityItem first = activity(1L, "活动1");
        ActivityItem expensive = activity(2L, "太贵");
        ActivityItem affordable = activity(3L, "预算内");

        ActivityPlanService.PlannedActivity w1 = window(
                "14:00-16:00", List.of(first),
                Map.of(1L, List.of(session(101L, 1L, 14, 15, "80", 10, "OPEN"))));
        ActivityPlanService.PlannedActivity w2 = window(
                "16:00-18:00", List.of(expensive, affordable),
                Map.of(
                        2L, List.of(session(201L, 2L, 16, 17, "50", 10, "OPEN")),
                        3L, List.of(session(301L, 3L, 16, 17, "20", 10, "OPEN"))));

        PlanCandidate plan = solver.solve(List.of(w1, w2), new BigDecimal("100")).getFirst();

        assertEquals(List.of(1L, 3L), plan.items().stream().map(item -> item.activity().id()).toList());
        assertEquals(new BigDecimal("100"), plan.totalCost());
    }

    @Test
    void shouldRejectCrossVenuePlanWhenTravelEvidenceExceedsAvailableGap() {
        ActivityItem first = activity(1L, "活动1");
        ActivityItem second = activity(2L, "活动2");

        ActivityPlanService.PlannedActivity w1 = window(
                "14:00-15:00", List.of(first),
                Map.of(1L, List.of(session(101L, 1L, 14, 15, "20", 10, "OPEN"))));
        ActivityPlanService.PlannedActivity w2 = window(
                "15:00-16:00", List.of(second),
                Map.of(2L, List.of(session(201L, 2L, 15, 16, "20", 10, "OPEN"))));

        List<TravelTimeEvidence> travel = List.of(
                new TravelTimeEvidence(101L, 102L, 30, "AMAP_ROUTE"));
        PlanCandidate plan = solver.solve(List.of(w1, w2), null, travel).getFirst();

        assertEquals(1, plan.matchedCount());
    }

    @Test
    void shouldAllowActivityOnlyPlanWhenConcreteDateSessionIsUnavailable() {
        ActivityItem first = activity(1L, "活动1");
        ActivityPlanService.PlannedActivity window = window("下午", List.of(first), Map.of());

        PlanCandidate plan = solver.solve(List.of(window)).getFirst();

        assertEquals(1, plan.matchedCount());
        assertNull(plan.items().getFirst().session());
    }

    @Test
    void shouldReturnSeveralLegalAlternativesForResponseAgentSelection() {
        ActivityItem first = activity(1L, "活动1");
        ActivityItem second = activity(2L, "活动2");
        ActivityItem third = activity(3L, "活动3");

        ActivityPlanService.PlannedActivity w1 = window(
                "14:00-16:00", List.of(first, second),
                Map.of(
                        1L, List.of(session(101L, 1L, 14, 15, "20", 10, "OPEN")),
                        2L, List.of(session(201L, 2L, 14, 15, "30", 10, "OPEN"))));
        ActivityPlanService.PlannedActivity w2 = window(
                "16:00-18:00", List.of(third),
                Map.of(3L, List.of(session(301L, 3L, 16, 17, "40", 10, "OPEN"))));

        List<PlanCandidate> plans = solver.solve(List.of(w1, w2));

        assertTrue(plans.size() >= 2);
        assertTrue(plans.stream().allMatch(plan -> plan.items().stream()
                .map(item -> item.activity().id()).distinct().count() == plan.items().size()));
        assertTrue(plans.stream().allMatch(plan -> plan.totalCost().compareTo(BigDecimal.ZERO) >= 0));
    }

    private ActivityPlanService.PlannedActivity window(
            String period,
            List<ActivityItem> candidates,
            Map<Long, List<ActivitySessionResponse>> sessions) {
        return new ActivityPlanService.PlannedActivity(
                period, null, SlotBundle.empty(), candidates, sessions, null);
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
                                            String price,
                                            Integer seats,
                                            String status) {
        LocalDateTime start = LocalDateTime.of(2026, 9, 26, startHour, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 26, endHour, 0);
        return new ActivitySessionResponse(
                sessionId, activityId, 100L + activityId, "场地", "VENUE",
                "西安", "雁塔", "地址", start, end, new BigDecimal(price),
                seats, status, null, null);
    }
}

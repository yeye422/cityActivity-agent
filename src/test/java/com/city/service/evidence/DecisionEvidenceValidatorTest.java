package com.city.service.evidence;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecisionEvidenceValidatorTest {

    private final DecisionEvidenceValidator validator = new DecisionEvidenceValidator();

    @Test
    void shouldRejectRecommendationActivityOutsideCurrentRunEvidence() {
        RunEvidenceStore store = new RunEvidenceStore("trace_recommend");
        store.recordActivity(activity(101L, "陶艺"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> validator.validateRecommendation(List.of(101L, 999L), store)
        );

        assertEquals(true, error.getMessage().contains("999"));
    }

    @Test
    void shouldResolveRecommendationFromCurrentRunEvidence() {
        RunEvidenceStore store = new RunEvidenceStore("trace_recommend_ok");
        store.recordActivity(activity(101L, "陶艺"));
        store.recordActivity(activity(202L, "银饰"));

        List<ActivityItem> resolved = validator.validateRecommendation(List.of(202L, 101L), store);

        assertEquals(List.of(202L, 101L), resolved.stream().map(ActivityItem::id).toList());
    }

    @Test
    void shouldRejectPlanSessionOutsideCurrentRunEvidence() {
        RunEvidenceStore store = new RunEvidenceStore("trace_plan_session");
        ActivityItem activity = activity(101L, "陶艺");
        ActivitySessionResponse session = session(1001L, 101L, 11L, 14, 16);
        store.recordActivity(activity);

        PlanCandidate plan = new PlanCandidate(
                List.of(new PlanCandidate.Item("14:00-16:00", activity, session)),
                BigDecimal.valueOf(120)
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> validator.validatePlan(plan, store)
        );

        assertEquals(true, error.getMessage().contains("1001"));
    }

    @Test
    void shouldRequireTravelEvidenceAcrossDifferentVenues() {
        RunEvidenceStore store = new RunEvidenceStore("trace_plan_travel");
        ActivityItem first = activity(101L, "陶艺");
        ActivityItem second = activity(202L, "桌游");
        ActivitySessionResponse firstSession = session(1001L, 101L, 11L, 14, 16);
        ActivitySessionResponse secondSession = session(2002L, 202L, 22L, 18, 20);
        store.recordActivities(List.of(first, second));
        store.recordSessions(List.of(firstSession, secondSession));

        PlanCandidate plan = new PlanCandidate(
                List.of(
                        new PlanCandidate.Item("14:00-16:00", first, firstSession),
                        new PlanCandidate.Item("18:00-20:00", second, secondSession)
                ),
                BigDecimal.valueOf(220)
        );

        assertThrows(IllegalStateException.class, () -> validator.validatePlan(plan, store));

        store.recordTravelEvidence(new TravelTimeEvidence(11L, 22L, 35, "TEST"));
        PlanCandidate validated = validator.validatePlan(plan, store);
        assertEquals(2, validated.items().size());
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 120, 0.9
        );
    }

    private ActivitySessionResponse session(Long sessionId,
                                            Long activityId,
                                            Long venueId,
                                            int startHour,
                                            int endHour) {
        return new ActivitySessionResponse(
                sessionId,
                activityId,
                venueId,
                "场地-" + venueId,
                "INDOOR",
                "上海",
                "浦东",
                "测试地址",
                LocalDateTime.of(2026, 9, 26, startHour, 0),
                LocalDateTime.of(2026, 9, 26, endHour, 0),
                BigDecimal.valueOf(100),
                10,
                "OPEN",
                null,
                null
        );
    }
}

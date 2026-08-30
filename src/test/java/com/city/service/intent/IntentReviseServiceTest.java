package com.city.service.intent;

import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentReviseServiceTest {

    private final IntentReviseService service = new IntentReviseService();

    @Test
    void shouldKeepActivityPlanIntentWhenUserIsAnsweringPlanClarification() {
        SessionState state = SessionState.fresh("sess_test", 1L, SourceMode.PUBLIC)
                .withIntent(Intent.ACTIVITY_PLAN)
                .withPhase(SessionPhase.CLARIFY);

        SlotBundle cityOnly = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, cityOnly, 0.95, List.of());

        IntentResult revised = service.revise(state, raw, "西安");

        assertEquals(Intent.ACTIVITY_PLAN, revised.intent());
        assertEquals(List.of("西安"), revised.slots().city());
    }

    @Test
    void shouldNotConvertNormalRecommendationIntoPlanWithoutPlanContext() {
        SessionState state = SessionState.fresh("sess_test", 1L, SourceMode.PUBLIC)
                .withPhase(SessionPhase.CLARIFY);
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, SlotBundle.empty(), 0.95, List.of());

        IntentResult revised = service.revise(state, raw, "西安");

        assertEquals(Intent.MEAL_RECOMMENDATION, revised.intent());
    }

    @Test
    void shouldNotUseHalfDayKeywordAloneAsPlanSignal() {
        IntentResult raw = new IntentResult(Intent.ACTIVITY_PLAN, SlotBundle.empty(), 0.95, List.of());

        IntentResult revised = service.revise(null, raw, "想找个半天的展览");

        assertEquals(Intent.MEAL_RECOMMENDATION, revised.intent());
    }

    @Test
    void shouldNotUseAllDayAvailabilityAsPlanSignal() {
        IntentResult raw = new IntentResult(Intent.ACTIVITY_PLAN, SlotBundle.empty(), 0.95, List.of());

        IntentResult revised = service.revise(null, raw, "周六全天都行，推荐几个");

        assertEquals(Intent.MEAL_RECOMMENDATION, revised.intent());
    }

    @Test
    void shouldPromoteExplicitPlanningActionToActivityPlan() {
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, SlotBundle.empty(), 0.95, List.of());

        IntentResult revised = service.revise(null, raw, "帮我安排周六一天");

        assertEquals(Intent.ACTIVITY_PLAN, revised.intent());
    }

    @Test
    void shouldPromoteExplicitItineraryRequestToActivityPlan() {
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, SlotBundle.empty(), 0.95, List.of());

        IntentResult revised = service.revise(null, raw, "给我做个一日行程");

        assertEquals(Intent.ACTIVITY_PLAN, revised.intent());
    }
}

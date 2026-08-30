package com.city.service.intent;

import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.service.time.TemporalValidator;
import com.city.service.time.TimeMutationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentReviseServiceTest {

    private final TemporalValidator temporalValidator = new TemporalValidator();
    private final IntentReviseService service = new IntentReviseService(
            new TimeMutationService(temporalValidator), temporalValidator);

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
}

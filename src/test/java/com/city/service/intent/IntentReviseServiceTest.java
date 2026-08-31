package com.city.service.intent;

import com.city.enums.ClarifyField;
import com.city.enums.ConstraintOperationType;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.model.ConstraintOperation;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntentReviseServiceTest {

    private final IntentReviseService service = new IntentReviseService();

    @Test
    void shouldKeepActivityPlanIntentWhenUserIsAnsweringPersistedPlanClarification() {
        SessionState state = SessionState.fresh("sess_test", 1L, SourceMode.PUBLIC)
                .withIntent(Intent.ACTIVITY_PLAN)
                .withPhase(SessionPhase.CLARIFY)
                .withPendingClarifyField(ClarifyField.CITY);

        ConstraintOperation cityAdd = new ConstraintOperation(
                "city", ConstraintOperationType.ADD, List.of("西安"), "西安");
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, 0.95, List.of(cityAdd));

        IntentResult revised = service.revise(state, raw, "西安");

        assertEquals(Intent.ACTIVITY_PLAN, revised.intent());
        assertEquals(List.of(cityAdd), revised.operations());
    }

    @Test
    void shouldNotKeepPlanIntentWhenClarifyPhaseHasNoPendingField() {
        SessionState state = SessionState.fresh("sess_test", 1L, SourceMode.PUBLIC)
                .withIntent(Intent.ACTIVITY_PLAN)
                .withPhase(SessionPhase.CLARIFY);
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, 0.95, List.of());

        IntentResult revised = service.revise(state, raw, "西安");

        assertEquals(Intent.MEAL_RECOMMENDATION, revised.intent());
    }

    @Test
    void shouldNotConvertNormalRecommendationIntoPlanWithoutPlanContext() {
        SessionState state = SessionState.fresh("sess_test", 1L, SourceMode.PUBLIC)
                .withPhase(SessionPhase.CLARIFY)
                .withPendingClarifyField(ClarifyField.CITY);
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, 0.95, List.of());

        IntentResult revised = service.revise(state, raw, "西安");

        assertEquals(Intent.MEAL_RECOMMENDATION, revised.intent());
    }

    @Test
    void shouldRespectSuccessfulModelPlanEvenWhenTextLooksLikeSingleActivity() {
        IntentResult raw = new IntentResult(Intent.ACTIVITY_PLAN, 0.95, List.of());

        IntentResult revised = service.revise(null, raw, "想找个半天的展览");

        assertEquals(Intent.ACTIVITY_PLAN, revised.intent());
        assertFalse(revised.fallback());
    }

    @Test
    void shouldRespectSuccessfulModelRecommendationEvenWhenTextContainsPlanningWords() {
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, 0.95, List.of());

        IntentResult revised = service.revise(null, raw, "帮我安排周六一天");

        assertEquals(Intent.MEAL_RECOMMENDATION, revised.intent());
    }

    @Test
    void shouldNotOverrideRecommendationWithSafetyKeywordsWhenModelSucceeded() {
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, 0.95, List.of());

        IntentResult revised = service.revise(null, raw, "暴雨天推荐几个室内展览");

        assertEquals(Intent.MEAL_RECOMMENDATION, revised.intent());
    }

    @Test
    void shouldNotForceClarificationOnlyBecauseConfidenceIsLow() {
        IntentResult raw = new IntentResult(Intent.MEAL_RECOMMENDATION, 0.2, List.of());

        IntentResult revised = service.revise(null, raw, "上海看展");

        assertEquals(Intent.MEAL_RECOMMENDATION, revised.intent());
        assertFalse(revised.fallback());
    }
}

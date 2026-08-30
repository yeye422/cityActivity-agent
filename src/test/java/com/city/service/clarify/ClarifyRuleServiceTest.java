package com.city.service.clarify;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClarifyRuleServiceTest {

    private final ClarifyRuleService service = new ClarifyRuleService();

    @Test
    void recommendationShouldOnlyRequireCity() {
        SlotBundle slots = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());

        List<ClarifyField> missing = service.missingRequiredFields(
                Intent.MEAL_RECOMMENDATION, slots, TimeConstraint.empty());

        assertTrue(missing.isEmpty());
    }

    @Test
    void recommendationShouldNotRequireBudget() {
        SlotBundle slots = new SlotBundle(
                List.of("上海"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertTrue(service.missingRequiredFields(
                Intent.MEAL_RECOMMENDATION, slots, TimeConstraint.empty()).isEmpty());
    }

    @Test
    void recommendationWithoutCityShouldAskCity() {
        List<ClarifyField> missing = service.missingRequiredFields(
                Intent.MEAL_RECOMMENDATION, SlotBundle.empty(), TimeConstraint.empty());

        assertEquals(List.of(ClarifyField.CITY), missing);
        assertEquals("你想看哪个城市的活动？", service.questionFor(ClarifyField.CITY));
    }

    @Test
    void planShouldRequireCityAndDate() {
        List<ClarifyField> missing = service.missingRequiredFields(
                Intent.ACTIVITY_PLAN, SlotBundle.empty(), TimeConstraint.empty());

        assertEquals(List.of(ClarifyField.CITY, ClarifyField.DATE), missing);
    }

    @Test
    void planWithCityAndDateShouldBeReady() {
        SlotBundle slots = new SlotBundle(
                List.of("成都"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        TimeConstraint time = new TimeConstraint(
                "周六", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                null, null, null);

        assertTrue(service.missingRequiredFields(
                Intent.ACTIVITY_PLAN, slots, time).isEmpty());
    }
}

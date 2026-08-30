package com.city.service.orchestrator;

import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CityOrchestratorServiceTest {

    @Test
    void recommendationQueryKeyShouldIgnoreRawAndResolvedAt() {
        TimeConstraint first = new TimeConstraint(
                "下午到晚上",
                LocalDate.of(2026, 9, 5),
                LocalDate.of(2026, 9, 5),
                LocalTime.of(14, 0),
                LocalTime.of(23, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TimeConstraint second = new TimeConstraint(
                "周六14点到23点",
                LocalDate.of(2026, 9, 5),
                LocalDate.of(2026, 9, 5),
                LocalTime.of(14, 0),
                LocalTime.of(23, 0),
                LocalDateTime.of(2026, 8, 30, 13, 0));

        assertEquals(
                CityOrchestratorService.recommendationQueryKey(state(first, Set.of("budget"))),
                CityOrchestratorService.recommendationQueryKey(state(second, Set.of("budget")))
        );
    }

    @Test
    void recommendationQueryKeyShouldChangeWhenActualTimeConstraintChanges() {
        TimeConstraint afternoon = new TimeConstraint(
                "下午",
                null,
                null,
                LocalTime.of(12, 0),
                LocalTime.of(18, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TimeConstraint evening = new TimeConstraint(
                "晚上",
                null,
                null,
                LocalTime.of(18, 0),
                LocalTime.of(23, 0),
                LocalDateTime.of(2026, 8, 30, 10, 1));

        assertNotEquals(
                CityOrchestratorService.recommendationQueryKey(state(afternoon, Set.of("budget"))),
                CityOrchestratorService.recommendationQueryKey(state(evening, Set.of("budget")))
        );
    }

    @Test
    void recommendationQueryKeyShouldNormalizeUnconstrainedSlotOrder() {
        TimeConstraint time = TimeConstraint.empty();

        assertEquals(
                CityOrchestratorService.recommendationQueryKey(state(time, Set.of("budget", "style"))),
                CityOrchestratorService.recommendationQueryKey(state(time, Set.of("style", "budget")))
        );
    }

    private SessionState state(TimeConstraint timeConstraint, Set<String> unconstrainedSlots) {
        SlotBundle slots = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        return new SessionState(
                "sess_test",
                1L,
                SessionPhase.RECOMMEND,
                SourceMode.PUBLIC,
                Intent.MEAL_RECOMMENDATION,
                slots,
                SlotBundle.empty(),
                unconstrainedSlots,
                timeConstraint,
                "",
                List.of(14L, 15L)
        );
    }
}

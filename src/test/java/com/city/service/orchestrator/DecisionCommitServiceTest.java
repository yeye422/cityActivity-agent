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

class DecisionCommitServiceTest {

    @Test
    void recommendationQueryKeyShouldIgnoreRawAndResolvedAt() {
        TimeConstraint first = new TimeConstraint(
                "下午到晚上",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                LocalTime.of(14, 0), LocalTime.of(23, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TimeConstraint second = new TimeConstraint(
                "周六14点到23点",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                LocalTime.of(14, 0), LocalTime.of(23, 0),
                LocalDateTime.of(2026, 8, 30, 13, 0));

        assertEquals(
                DecisionCommitService.recommendationQueryKey(state(first, Set.of("budget"))),
                DecisionCommitService.recommendationQueryKey(state(second, Set.of("budget"))));
    }

    @Test
    void recommendationQueryKeyShouldChangeWhenActualTimeConstraintChanges() {
        TimeConstraint afternoon = new TimeConstraint(
                "下午", null, null, LocalTime.of(12, 0), LocalTime.of(18, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TimeConstraint evening = new TimeConstraint(
                "晚上", null, null, LocalTime.of(18, 0), LocalTime.of(23, 0),
                LocalDateTime.of(2026, 8, 30, 10, 1));

        assertNotEquals(
                DecisionCommitService.recommendationQueryKey(state(afternoon, Set.of("budget"))),
                DecisionCommitService.recommendationQueryKey(state(evening, Set.of("budget"))));
    }

    @Test
    void recommendationQueryKeyShouldNormalizeUnconstrainedSlotOrder() {
        assertEquals(
                DecisionCommitService.recommendationQueryKey(state(TimeConstraint.empty(), Set.of("budget", "style"))),
                DecisionCommitService.recommendationQueryKey(state(TimeConstraint.empty(), Set.of("style", "budget"))));
    }

    private SessionState state(TimeConstraint timeConstraint, Set<String> unconstrainedSlots) {
        SlotBundle slots = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of("室内"));
        return new SessionState(
                "sess_test", 1L, SessionPhase.RECOMMEND, SourceMode.PUBLIC,
                Intent.ACTIVITY_RECOMMENDATION, slots, SlotBundle.empty(), unconstrainedSlots,
                timeConstraint, null, "", null, List.of(14L, 15L));
    }
}

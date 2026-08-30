package com.city.service.time;

import com.city.enums.TemporalMode;
import com.city.model.TemporalMutation;
import com.city.model.TimeConstraint;
import com.city.model.TimeResolutionResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeResolutionServiceTest {

    private final TemporalValidator validator = new TemporalValidator();
    private final TimeResolutionService service = new TimeResolutionService(
            validator,
            new TimeMutationService(validator),
            new TimeExpressionParser()
    );

    @Test
    void shouldPreferReliableLlmTemporal() {
        TemporalMutation temporal = new TemporalMutation(
                "下周六下午三点左右",
                TemporalMode.SET,
                LocalDate.of(2026, 9, 5),
                LocalDate.of(2026, 9, 5),
                TemporalMode.SET,
                LocalTime.of(14, 0),
                LocalTime.of(16, 0),
                true,
                0.96
        );

        TimeResolutionResult result = service.resolve(TimeConstraint.empty(), temporal, "下周六下午三点左右");

        assertEquals(TimeResolutionResult.Status.LLM_SUCCESS, result.status());
        assertEquals(LocalDate.of(2026, 9, 5), result.timeConstraint().dateStart());
        assertEquals(LocalTime.of(14, 0), result.timeConstraint().startTime());
    }

    @Test
    void shouldTreatReliableNoTimeDecisionAsUnchanged() {
        TimeConstraint historical = new TimeConstraint(
                "周六下午",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                LocalTime.of(12, 0), LocalTime.of(18, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TemporalMutation noTimeMentioned = new TemporalMutation(
                "",
                TemporalMode.KEEP, null, null,
                TemporalMode.KEEP, null, null,
                false, 1.0);

        TimeResolutionResult result = service.resolve(historical, noTimeMentioned, "预算改成200以内");

        assertEquals(TimeResolutionResult.Status.UNCHANGED, result.status());
        assertEquals(historical.dateStart(), result.timeConstraint().dateStart());
        assertEquals(historical.startTime(), result.timeConstraint().startTime());
    }

    @Test
    void shouldFallbackToJavaWhenLlmTemporalIsLowConfidence() {
        TemporalMutation temporal = new TemporalMutation(
                "明天晚上",
                TemporalMode.SET,
                LocalDate.of(2099, 1, 1),
                LocalDate.of(2099, 1, 1),
                TemporalMode.SET,
                LocalTime.of(18, 0),
                LocalTime.of(23, 0),
                false,
                0.20
        );

        TimeResolutionResult result = service.resolve(TimeConstraint.empty(), temporal, "明天晚上");

        assertEquals(TimeResolutionResult.Status.JAVA_FALLBACK, result.status());
        assertTrue(result.timeConstraint().hasDate());
        assertEquals(18, result.timeConstraint().startTime().getHour());
        assertFalse(LocalDate.of(2099, 1, 1).equals(result.timeConstraint().dateStart()));
    }

    @Test
    void shouldClarifyWhenBothLlmAndJavaFailToUnderstandTime() {
        TemporalMutation temporal = new TemporalMutation(
                "下个月找个不太早也不太晚的时候",
                TemporalMode.KEEP, null, null,
                TemporalMode.KEEP, null, null,
                false,
                0.20
        );

        TimeResolutionResult result = service.resolve(
                TimeConstraint.empty(), temporal, "下个月找个不太早也不太晚的时候");

        assertEquals(TimeResolutionResult.Status.CLARIFY, result.status());
        assertTrue(result.needsClarification());
    }

    @Test
    void shouldClearOnlyTimeOfDayWithJavaFallback() {
        TimeConstraint historical = new TimeConstraint(
                "周六下午",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                LocalTime.of(12, 0), LocalTime.of(18, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TemporalMutation unavailable = TemporalMutation.keep();

        TimeResolutionResult result = service.resolve(historical, unavailable, "几点都行");

        assertEquals(TimeResolutionResult.Status.JAVA_FALLBACK, result.status());
        assertTrue(result.timeConstraint().hasDate());
        assertFalse(result.timeConstraint().hasTime());
    }

    @Test
    void shouldKeepHistoricalTimeWhenLlmUnderstandsExplicitKeepReference() {
        TimeConstraint historical = new TimeConstraint(
                "周六下午",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                LocalTime.of(12, 0), LocalTime.of(18, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TemporalMutation temporal = new TemporalMutation(
                "还是之前那个时间",
                TemporalMode.KEEP, null, null,
                TemporalMode.KEEP, null, null,
                false, 0.95);

        TimeResolutionResult result = service.resolve(historical, temporal, "还是之前那个时间");

        assertEquals(TimeResolutionResult.Status.UNCHANGED, result.status());
        assertEquals(historical.dateStart(), result.timeConstraint().dateStart());
        assertEquals(historical.startTime(), result.timeConstraint().startTime());
        assertFalse(result.needsClarification());
    }
}

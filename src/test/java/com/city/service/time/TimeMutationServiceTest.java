package com.city.service.time;

import com.city.enums.TemporalMode;
import com.city.model.TemporalMutation;
import com.city.model.TimeConstraint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeMutationServiceTest {

    private final TimeMutationService service = new TimeMutationService(new TemporalValidator());

    @Test
    void shouldKeepDateWhenOnlyTimeChanges() {
        TimeConstraint historical = new TimeConstraint(
                "周六下午",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                LocalTime.of(12, 0), LocalTime.of(18, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TemporalMutation mutation = new TemporalMutation(
                "晚上",
                TemporalMode.KEEP, null, null,
                TemporalMode.SET, LocalTime.of(18, 0), LocalTime.of(23, 0),
                false, 0.95);

        TimeConstraint result = service.apply(historical, mutation);

        assertEquals(LocalDate.of(2026, 9, 5), result.dateStart());
        assertEquals(LocalTime.of(18, 0), result.startTime());
        assertEquals(LocalTime.of(23, 0), result.endTime());
    }

    @Test
    void shouldKeepTimeWhenOnlyDateChanges() {
        TimeConstraint historical = new TimeConstraint(
                "周六下午",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                LocalTime.of(12, 0), LocalTime.of(18, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TemporalMutation mutation = new TemporalMutation(
                "改周日",
                TemporalMode.SET, LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 6),
                TemporalMode.KEEP, null, null,
                false, 0.96);

        TimeConstraint result = service.apply(historical, mutation);

        assertEquals(LocalDate.of(2026, 9, 6), result.dateStart());
        assertEquals(LocalTime.of(12, 0), result.startTime());
        assertEquals(LocalTime.of(18, 0), result.endTime());
    }

    @Test
    void shouldClearOnlyTimeOfDay() {
        TimeConstraint historical = new TimeConstraint(
                "周六下午",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                LocalTime.of(12, 0), LocalTime.of(18, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TemporalMutation mutation = new TemporalMutation(
                "几点都行",
                TemporalMode.KEEP, null, null,
                TemporalMode.CLEAR, null, null,
                false, 0.98);

        TimeConstraint result = service.apply(historical, mutation);

        assertTrue(result.hasDate());
        assertFalse(result.hasTime());
    }

    @Test
    void shouldClearAllTimeConstraints() {
        TimeConstraint historical = new TimeConstraint(
                "周六下午",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                LocalTime.of(12, 0), LocalTime.of(18, 0),
                LocalDateTime.of(2026, 8, 30, 10, 0));
        TemporalMutation mutation = new TemporalMutation(
                "时间不限",
                TemporalMode.CLEAR, null, null,
                TemporalMode.CLEAR, null, null,
                false, 0.99);

        TimeConstraint result = service.apply(historical, mutation);

        assertFalse(result.hasConstraint());
    }
}

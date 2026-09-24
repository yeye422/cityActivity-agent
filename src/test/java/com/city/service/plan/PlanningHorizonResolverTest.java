package com.city.service.plan;

import com.city.model.TimeConstraint;
import com.city.model.context.PlanningHorizon;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class PlanningHorizonResolverTest {

    private final PlanningHorizonResolver resolver = new PlanningHorizonResolver();

    @Test
    void shouldKeepExactExplicitTimeWithoutBucketExpansion() {
        LocalDate date = LocalDate.of(2026, 9, 27);
        PlanningHorizon horizon = resolver.resolve(new TimeConstraint(
                "15点到17点", date, date,
                LocalTime.of(15, 0), LocalTime.of(17, 0), null));

        assertEquals(1, horizon.ranges().size());
        assertEquals(date.atTime(15, 0), horizon.ranges().getFirst().startAt());
        assertEquals(date.atTime(17, 0), horizon.ranges().getFirst().endAt());
    }

    @Test
    void shouldExpandDateRangeByDayWithoutCreatingTimeBuckets() {
        LocalDate saturday = LocalDate.of(2026, 9, 26);
        PlanningHorizon horizon = resolver.resolve(new TimeConstraint(
                "周末", saturday, saturday.plusDays(1), null, null, null));

        assertEquals(2, horizon.ranges().size());
        assertEquals(saturday.atTime(8, 0), horizon.ranges().getFirst().startAt());
        assertEquals(saturday.atTime(23, 0), horizon.ranges().getFirst().endAt());
        assertEquals(saturday.plusDays(1).atTime(8, 0), horizon.ranges().get(1).startAt());
    }
}

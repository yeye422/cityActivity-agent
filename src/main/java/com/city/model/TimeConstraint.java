package com.city.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 用户本轮明确表达的绝对日期/时段。 */
public record TimeConstraint(
        String raw,
        LocalDate dateStart,
        LocalDate dateEnd,
        LocalTime startTime,
        LocalTime endTime,
        LocalDateTime resolvedAt
) {
    public static TimeConstraint empty() {
        return new TimeConstraint("", null, null, null, null, null);
    }

    public boolean hasDate() {
        return dateStart != null && dateEnd != null;
    }

    public boolean hasTime() {
        return startTime != null && endTime != null;
    }

    public boolean hasConstraint() {
        return hasDate() || hasTime();
    }
}

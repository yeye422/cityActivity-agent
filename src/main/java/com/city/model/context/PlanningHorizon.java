package com.city.model.context;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Planning 的真实可规划时间边界。
 *
 * <p>它表达“Agent 可以在哪些绝对日期时间范围内安排活动”，不负责把范围切成固定 bucket。</p>
 */
public record PlanningHorizon(List<Range> ranges) {

    public PlanningHorizon {
        ranges = ranges == null
                ? List.of()
                : ranges.stream()
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(Range::startAt))
                        .toList();
    }

    public static PlanningHorizon empty() {
        return new PlanningHorizon(List.of());
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public boolean contains(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) return false;
        return ranges.stream().anyMatch(range -> range.contains(startAt, endAt));
    }

    public boolean contains(Range requested) {
        return requested != null && contains(requested.startAt(), requested.endAt());
    }

    public record Range(LocalDateTime startAt, LocalDateTime endAt) {
        public Range {
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
            if (!startAt.isBefore(endAt)) {
                throw new IllegalArgumentException("planning range 必须满足 startAt < endAt");
            }
        }

        public boolean contains(LocalDateTime start, LocalDateTime end) {
            return start != null
                    && end != null
                    && !start.isBefore(startAt)
                    && !end.isAfter(endAt)
                    && start.isBefore(end);
        }

        @Override
        public String toString() {
            return startAt + "/" + endAt;
        }
    }
}

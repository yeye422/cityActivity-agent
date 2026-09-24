package com.city.model.tool;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.context.PlanningHorizon;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** PlanningAgent 可见的 run-scoped 候选与真实 session 快照。 */
public record PlanningDiscoveryToolResult(
        List<SearchRange> searchedRanges,
        List<Candidate> candidates
) {
    public PlanningDiscoveryToolResult {
        searchedRanges = searchedRanges == null ? List.of() : List.copyOf(searchedRanges);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static PlanningDiscoveryToolResult from(
            List<PlanningHorizon.Range> ranges,
            List<ActivityItem> activities,
            Map<Long, List<ActivitySessionResponse>> sessionsByActivityId
    ) {
        List<SearchRange> safeRanges = ranges == null
                ? List.of()
                : ranges.stream().map(SearchRange::from).toList();
        Map<Long, List<ActivitySessionResponse>> safeSessions =
                sessionsByActivityId == null ? Map.of() : sessionsByActivityId;
        List<Candidate> result = activities == null
                ? List.of()
                : activities.stream()
                        .filter(item -> item != null && item.id() != null)
                        .map(item -> Candidate.from(
                                item,
                                safeSessions.getOrDefault(item.id(), List.of())
                        ))
                        .toList();
        return new PlanningDiscoveryToolResult(safeRanges, result);
    }

    public record SearchRange(LocalDateTime startAt, LocalDateTime endAt) {
        private static SearchRange from(PlanningHorizon.Range range) {
            return new SearchRange(range.startAt(), range.endAt());
        }
    }

    public record Candidate(
            Long activityId,
            String name,
            Integer durationMinutes,
            double matchScore,
            List<Session> sessions
    ) {
        private static Candidate from(ActivityItem item, List<ActivitySessionResponse> sessions) {
            return new Candidate(
                    item.id(),
                    item.name(),
                    item.durationMinutes(),
                    item.matchScore(),
                    sessions == null ? List.of() : sessions.stream().map(Session::from).toList()
            );
        }
    }

    public record Session(
            Long sessionId,
            Long venueId,
            String venueName,
            LocalDateTime startAt,
            LocalDateTime endAt,
            BigDecimal price,
            Integer remainingSeats,
            String status
    ) {
        private static Session from(ActivitySessionResponse session) {
            return new Session(
                    session.sessionId(),
                    session.venueId(),
                    session.venueName(),
                    session.startAt(),
                    session.endAt(),
                    session.price(),
                    session.remainingSeats(),
                    session.status()
            );
        }
    }
}

package com.city.model.tool;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.service.plan.ActivityPlanService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** PlanningAgent 可见的窗口级候选/场次视图。 */
public record PlanningDiscoveryToolResult(
        List<Window> windows
) {
    public PlanningDiscoveryToolResult {
        windows = windows == null ? List.of() : List.copyOf(windows);
    }

    public static PlanningDiscoveryToolResult from(List<ActivityPlanService.PlannedActivity> planned) {
        List<Window> windows = planned == null ? List.of() : planned.stream()
                .filter(window -> window != null)
                .map(Window::from)
                .toList();
        return new PlanningDiscoveryToolResult(windows);
    }

    public record Window(
            String period,
            List<Candidate> candidates
    ) {
        private static Window from(ActivityPlanService.PlannedActivity window) {
            return new Window(
                    window.period(),
                    window.candidates().stream()
                            .filter(item -> item != null && item.id() != null)
                            .map(item -> Candidate.from(
                                    item,
                                    window.sessionsByActivityId().getOrDefault(item.id(), List.of())
                            ))
                            .toList()
            );
        }
    }

    public record Candidate(
            Long activityId,
            String name,
            double matchScore,
            List<Session> sessions
    ) {
        private static Candidate from(ActivityItem item, List<ActivitySessionResponse> sessions) {
            return new Candidate(
                    item.id(),
                    item.name(),
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

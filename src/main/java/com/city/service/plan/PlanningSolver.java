package com.city.service.plan;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.PlanCandidate;
import com.city.model.TravelTimeEvidence;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确定性规划求解器。
 *
 * <p>只处理领域模型和已验证证据可以可靠判断的硬约束：活动去重、场次状态、剩余名额、
 * 场次时间冲突、已知场次价格的累计预算，以及存在 TravelTimeEvidence 时的跨场地移动时间。</p>
 *
 * <p>Solver 会生成多个合法 PlanCandidate，再按覆盖窗口数、活动匹配分和已知成本排序。
 * Response Agent 后续只能在这些合法候选中选择和解释，而不是自行拼装活动/场次。</p>
 */
public final class PlanningSolver {

    private static final int MAX_PLAN_CANDIDATES = 5;
    private static final int MAX_RAW_PLANS = 300;
    private static final int MAX_SESSION_CHOICES_PER_ACTIVITY = 3;

    public List<PlanCandidate> solve(List<ActivityPlanService.PlannedActivity> windows) {
        return solve(windows, null, List.of());
    }

    public List<PlanCandidate> solve(List<ActivityPlanService.PlannedActivity> windows,
                                     BigDecimal maxBudget) {
        return solve(windows, maxBudget, List.of());
    }

    public List<PlanCandidate> solve(List<ActivityPlanService.PlannedActivity> windows,
                                     BigDecimal maxBudget,
                                     List<TravelTimeEvidence> travelTimeEvidence) {
        if (windows == null || windows.isEmpty()) return List.of();
        if (maxBudget != null && maxBudget.signum() < 0) {
            throw new IllegalArgumentException("maxBudget 不能为负数");
        }
        Map<RouteKey, Integer> travelMinutes = travelMinutes(travelTimeEvidence);

        List<PlanCandidate> rawPlans = new ArrayList<>();
        backtrack(
                windows,
                0,
                new ArrayList<>(),
                new LinkedHashSet<>(),
                BigDecimal.ZERO,
                maxBudget,
                travelMinutes,
                rawPlans
        );

        Map<String, PlanCandidate> unique = new LinkedHashMap<>();
        for (PlanCandidate candidate : rawPlans) {
            if (candidate == null || candidate.items().isEmpty()) continue;
            unique.putIfAbsent(signature(candidate), candidate);
        }

        Comparator<PlanCandidate> comparator = Comparator
                .<PlanCandidate>comparingInt(PlanCandidate::matchedCount).reversed()
                .thenComparing(Comparator.comparingDouble(this::matchScore).reversed())
                .thenComparing(PlanCandidate::totalCost);

        return unique.values().stream()
                .sorted(comparator)
                .limit(MAX_PLAN_CANDIDATES)
                .toList();
    }

    private void backtrack(List<ActivityPlanService.PlannedActivity> windows,
                           int index,
                           List<PlanCandidate.Item> selected,
                           Set<Long> usedActivityIds,
                           BigDecimal currentCost,
                           BigDecimal maxBudget,
                           Map<RouteKey, Integer> travelMinutes,
                           List<PlanCandidate> output) {
        if (output.size() >= MAX_RAW_PLANS) return;
        if (index >= windows.size()) {
            if (!selected.isEmpty()) {
                output.add(new PlanCandidate(List.copyOf(selected), currentCost));
            }
            return;
        }

        ActivityPlanService.PlannedActivity window = windows.get(index);
        if (window == null) {
            backtrack(windows, index + 1, selected, usedActivityIds, currentCost, maxBudget, travelMinutes, output);
            return;
        }

        // SKIP 始终是合法选择：不为了填满所有窗口而制造过密或低质量安排。
        backtrack(windows, index + 1, selected, usedActivityIds, currentCost, maxBudget, travelMinutes, output);

        for (Selection selection : choices(window)) {
            if (output.size() >= MAX_RAW_PLANS) return;
            ActivityItem activity = selection.activity();
            if (activity == null || activity.id() == null || usedActivityIds.contains(activity.id())) continue;
            if (selection.session() != null && conflicts(selection.session(), selected, travelMinutes)) continue;

            BigDecimal addition = price(selection.session());
            if (!withinBudget(currentCost, addition, maxBudget)) continue;

            PlanCandidate.Item item = new PlanCandidate.Item(window.period(), activity, selection.session());
            selected.add(item);
            usedActivityIds.add(activity.id());
            backtrack(
                    windows,
                    index + 1,
                    selected,
                    usedActivityIds,
                    currentCost.add(addition),
                    maxBudget,
                    travelMinutes,
                    output
            );
            usedActivityIds.remove(activity.id());
            selected.removeLast();
        }
    }

    private List<Selection> choices(ActivityPlanService.PlannedActivity window) {
        List<ActivityItem> candidates = candidates(window);
        Map<Long, List<ActivitySessionResponse>> sessionsByActivityId =
                window.sessionsByActivityId() == null ? Map.of() : window.sessionsByActivityId();
        boolean concreteSessionRequired = !sessionsByActivityId.isEmpty();
        List<Selection> result = new ArrayList<>();

        for (ActivityItem candidate : candidates) {
            if (candidate == null || candidate.id() == null) continue;
            if (!concreteSessionRequired) {
                result.add(new Selection(candidate, null));
                continue;
            }

            sessionsByActivityId.getOrDefault(candidate.id(), List.of()).stream()
                    .filter(session -> validSession(session, candidate.id()))
                    .limit(MAX_SESSION_CHOICES_PER_ACTIVITY)
                    .map(session -> new Selection(candidate, session))
                    .forEach(result::add);
        }
        return result;
    }

    private List<ActivityItem> candidates(ActivityPlanService.PlannedActivity window) {
        if (window.candidates() != null && !window.candidates().isEmpty()) {
            return window.candidates();
        }
        return window.activity() == null ? List.of() : List.of(window.activity());
    }

    private boolean validSession(ActivitySessionResponse session, Long activityId) {
        if (session == null || session.sessionId() == null) return false;
        if (session.activityId() != null && !session.activityId().equals(activityId)) return false;
        if (!"OPEN".equalsIgnoreCase(session.status())) return false;
        if (session.remainingSeats() != null && session.remainingSeats() <= 0) return false;
        return session.startAt() != null
                && session.endAt() != null
                && session.startAt().isBefore(session.endAt());
    }

    private boolean conflicts(ActivitySessionResponse candidate,
                              List<PlanCandidate.Item> selected,
                              Map<RouteKey, Integer> travelMinutes) {
        for (PlanCandidate.Item item : selected) {
            ActivitySessionResponse existing = item.session();
            if (existing == null || existing.startAt() == null || existing.endAt() == null) continue;
            if (overlaps(candidate.startAt(), candidate.endAt(), existing.startAt(), existing.endAt())) {
                return true;
            }
            if (insufficientTravelGap(existing, candidate, travelMinutes)) {
                return true;
            }
        }
        return false;
    }

    private boolean insufficientTravelGap(ActivitySessionResponse first,
                                          ActivitySessionResponse second,
                                          Map<RouteKey, Integer> travelMinutes) {
        if (first.venueId() == null || second.venueId() == null || first.venueId().equals(second.venueId())) {
            return false;
        }

        if (!first.endAt().isAfter(second.startAt())) {
            return gapTooShort(
                    first.venueId(), second.venueId(), first.endAt(), second.startAt(), travelMinutes);
        }
        if (!second.endAt().isAfter(first.startAt())) {
            return gapTooShort(
                    second.venueId(), first.venueId(), second.endAt(), first.startAt(), travelMinutes);
        }
        return false;
    }

    private boolean gapTooShort(Long fromVenueId,
                                Long toVenueId,
                                LocalDateTime fromTime,
                                LocalDateTime toTime,
                                Map<RouteKey, Integer> travelMinutes) {
        Integer requiredMinutes = travelMinutes.get(new RouteKey(fromVenueId, toVenueId));
        if (requiredMinutes == null) return false;
        long availableMinutes = Duration.between(fromTime, toTime).toMinutes();
        return availableMinutes < requiredMinutes;
    }

    private Map<RouteKey, Integer> travelMinutes(List<TravelTimeEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return Map.of();
        Map<RouteKey, Integer> result = new LinkedHashMap<>();
        for (TravelTimeEvidence item : evidence) {
            if (item == null) continue;
            result.putIfAbsent(
                    new RouteKey(item.fromVenueId(), item.toVenueId()),
                    item.durationMinutes());
        }
        return Map.copyOf(result);
    }

    private boolean overlaps(LocalDateTime start,
                             LocalDateTime end,
                             LocalDateTime existingStart,
                             LocalDateTime existingEnd) {
        return start.isBefore(existingEnd) && end.isAfter(existingStart);
    }

    private boolean withinBudget(BigDecimal current,
                                 BigDecimal addition,
                                 BigDecimal maxBudget) {
        return maxBudget == null || current.add(addition).compareTo(maxBudget) <= 0;
    }

    private BigDecimal price(ActivitySessionResponse session) {
        return session == null || session.price() == null ? BigDecimal.ZERO : session.price();
    }

    private double matchScore(PlanCandidate candidate) {
        return candidate.items().stream()
                .map(PlanCandidate.Item::activity)
                .filter(activity -> activity != null)
                .mapToDouble(ActivityItem::matchScore)
                .sum();
    }

    private String signature(PlanCandidate candidate) {
        return candidate.items().stream()
                .map(item -> item.period() + ":" + item.activity().id() + ":"
                        + (item.session() == null ? "-" : item.session().sessionId()))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private record RouteKey(Long fromVenueId, Long toVenueId) {}

    private record Selection(ActivityItem activity, ActivitySessionResponse session) {}
}

package com.city.service.plan;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.PlanCandidate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确定性规划求解器。
 *
 * <p>当前阶段只处理现有领域模型可以可靠判断的硬约束：活动去重、场次状态、剩余名额、
 * 场次时间冲突和已知场次价格的累计预算。地图路程时间在接入稳定 Map/TravelTime 证据前不做猜测。</p>
 */
public final class PlanningSolver {

    public List<PlanCandidate> solve(List<ActivityPlanService.PlannedActivity> windows) {
        return solve(windows, null);
    }

    public List<PlanCandidate> solve(List<ActivityPlanService.PlannedActivity> windows,
                                     BigDecimal maxBudget) {
        if (windows == null || windows.isEmpty()) return List.of();
        if (maxBudget != null && maxBudget.signum() < 0) {
            throw new IllegalArgumentException("maxBudget 不能为负数");
        }

        List<PlanCandidate.Item> selected = new ArrayList<>();
        Set<Long> usedActivityIds = new LinkedHashSet<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (ActivityPlanService.PlannedActivity window : windows) {
            if (window == null) continue;
            Selection selection = choose(window, usedActivityIds, selected, totalCost, maxBudget);
            if (selection == null) continue; // SKIP：该窗口没有合法候选时允许留空。

            selected.add(new PlanCandidate.Item(window.period(), selection.activity(), selection.session()));
            usedActivityIds.add(selection.activity().id());
            totalCost = totalCost.add(price(selection.session()));
        }

        if (selected.isEmpty()) return List.of();
        return List.of(new PlanCandidate(selected, totalCost));
    }

    private Selection choose(ActivityPlanService.PlannedActivity window,
                             Set<Long> usedActivityIds,
                             List<PlanCandidate.Item> selected,
                             BigDecimal currentCost,
                             BigDecimal maxBudget) {
        List<ActivityItem> candidates = candidates(window);
        Map<Long, List<ActivitySessionResponse>> sessionsByActivityId =
                window.sessionsByActivityId() == null ? Map.of() : window.sessionsByActivityId();
        boolean concreteSessionRequired = !sessionsByActivityId.isEmpty();

        for (ActivityItem candidate : candidates) {
            if (candidate == null || candidate.id() == null || usedActivityIds.contains(candidate.id())) continue;

            if (!concreteSessionRequired) {
                if (withinBudget(currentCost, BigDecimal.ZERO, maxBudget)) {
                    return new Selection(candidate, null);
                }
                continue;
            }

            List<ActivitySessionResponse> sessions = sessionsByActivityId.getOrDefault(candidate.id(), List.of());
            for (ActivitySessionResponse session : sessions) {
                if (!validSession(session, candidate.id())) continue;
                if (conflicts(session, selected)) continue;
                BigDecimal sessionPrice = price(session);
                if (!withinBudget(currentCost, sessionPrice, maxBudget)) continue;
                return new Selection(candidate, session);
            }
        }
        return null;
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
                              List<PlanCandidate.Item> selected) {
        for (PlanCandidate.Item item : selected) {
            ActivitySessionResponse existing = item.session();
            if (existing == null || existing.startAt() == null || existing.endAt() == null) continue;
            if (overlaps(candidate.startAt(), candidate.endAt(), existing.startAt(), existing.endAt())) {
                return true;
            }
        }
        return false;
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

    private record Selection(ActivityItem activity, ActivitySessionResponse session) {}
}

package com.city.service.plan;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.PlanCandidate;
import com.city.model.TravelTimeEvidence;
import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanValidationResult;
import com.city.service.evidence.PlanningEvidenceRegistry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把 PlanningAgent 的 ID 引用绑定回本轮证据，再复用 PlanningSolver 做最终硬约束校验。
 */
@Service
public class PlanProposalValidationService {

    private final PlanningSolver planningSolver = new PlanningSolver();

    public PlanValidationResult validate(
            PlanProposal proposal,
            PlanningEvidenceRegistry evidenceRegistry,
            BigDecimal maxBudget,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        if (proposal == null || proposal.items().isEmpty()) {
            return PlanValidationResult.invalid(List.of(violation(
                    "EMPTY_PLAN", "", null, null,
                    "规划方案不能为空", "至少选择一个已检索到的活动"
            )));
        }
        if (evidenceRegistry == null) {
            return PlanValidationResult.invalid(List.of(violation(
                    "MISSING_EVIDENCE", "", null, null,
                    "缺少本轮规划证据", "先调用规划候选检索 Tool"
            )));
        }

        List<PlanValidationResult.Violation> violations = new ArrayList<>();
        Set<Long> usedActivityIds = new LinkedHashSet<>();
        List<ActivityPlanService.PlannedActivity> restrictedWindows = new ArrayList<>();

        for (PlanProposal.Item proposed : proposal.items()) {
            if (!evidenceRegistry.hasPeriod(proposed.period())) {
                violations.add(violation(
                        "UNKNOWN_PERIOD", proposed.period(), proposed.activityId(), proposed.sessionId(),
                        "该时段未由规划检索 Tool 暴露", "从已暴露的 period 中重新选择"
                ));
                continue;
            }

            ActivityItem activity = evidenceRegistry.activity(proposed.period(), proposed.activityId());
            if (activity == null) {
                violations.add(violation(
                        "ACTIVITY_NOT_EXPOSED", proposed.period(), proposed.activityId(), proposed.sessionId(),
                        "activityId 不属于该时段已验证候选", "改选该时段 Tool 返回的 activityId"
                ));
                continue;
            }
            if (!usedActivityIds.add(proposed.activityId())) {
                violations.add(violation(
                        "DUPLICATE_ACTIVITY", proposed.period(), proposed.activityId(), proposed.sessionId(),
                        "同一个活动不能在一个计划中重复安排", "为该时段选择其他活动"
                ));
                continue;
            }

            ActivityPlanService.PlannedActivity sourceWindow = evidenceRegistry.windows().stream()
                    .filter(window -> proposed.period().equals(window.period()))
                    .findFirst()
                    .orElseThrow();
            boolean windowHasConcreteSessions = !sourceWindow.sessionsByActivityId().isEmpty();
            List<ActivitySessionResponse> exposedSessions =
                    sourceWindow.sessionsByActivityId().getOrDefault(activity.id(), List.of());
            ActivitySessionResponse session = null;
            Map<Long, List<ActivitySessionResponse>> sessions;
            if (proposed.sessionId() != null) {
                session = evidenceRegistry.session(
                        proposed.period(), proposed.activityId(), proposed.sessionId());
                if (session == null) {
                    violations.add(violation(
                            "SESSION_NOT_EXPOSED", proposed.period(), proposed.activityId(), proposed.sessionId(),
                            "sessionId 不属于该活动在该时段的已验证场次", "移除该 sessionId 或改选已暴露 OPEN 场次"
                    ));
                    continue;
                }
                sessions = Map.of(activity.id(), List.of(session));
            } else if (windowHasConcreteSessions) {
                if (exposedSessions.isEmpty()) {
                    violations.add(violation(
                            "ACTIVITY_HAS_NO_AVAILABLE_SESSION", proposed.period(), proposed.activityId(), null,
                            "该活动在目标日期/时段没有已验证可参加场次", "改选该时段具有 OPEN 场次的 activityId"
                    ));
                    continue;
                }
                // sessionId 为空表示把具体场次绑定交给确定性 Solver。
                sessions = Map.of(activity.id(), exposedSessions);
            } else {
                sessions = Map.of();
            }
            restrictedWindows.add(new ActivityPlanService.PlannedActivity(
                    proposed.period(),
                    activity,
                    sourceWindow.querySlots(),
                    List.of(activity),
                    sessions,
                    session
            ));
        }

        if (!violations.isEmpty()) {
            return PlanValidationResult.invalid(violations);
        }

        List<TravelTimeEvidence> safeTravel = travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);
        violations.addAll(missingTravelEvidence(restrictedWindows, safeTravel));
        violations.addAll(explicitConstraintViolations(restrictedWindows, maxBudget, safeTravel));
        if (!violations.isEmpty()) {
            return PlanValidationResult.invalid(violations);
        }

        List<PlanCandidate> legal = planningSolver.solve(
                restrictedWindows,
                maxBudget,
                safeTravel
        );
        PlanCandidate accepted = legal.stream()
                .filter(candidate -> candidate.items().size() == proposal.items().size())
                .filter(candidate -> matchesProposal(candidate, proposal))
                .findFirst()
                .orElse(null);
        if (accepted != null) {
            return PlanValidationResult.valid(accepted);
        }

        return PlanValidationResult.invalid(List.of(violation(
                "HARD_CONSTRAINT_CONFLICT", "", null, null,
                "该组合未通过时间、预算、场次或交通硬约束校验",
                "保留更符合 UserGoal 的活动，调整冲突时段的活动或场次后重新 validate_plan"
        )));
    }

    /**
     * 对有具体场次的方案按实际开始时间排序。相邻场次位于不同场地时，必须存在 from->to 的
     * TravelTimeEvidence；缺证据不是“默认可行”，而是要求 Agent 先调用 get_travel_time。
     */
    private List<PlanValidationResult.Violation> missingTravelEvidence(
            List<ActivityPlanService.PlannedActivity> windows,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        List<ActivityPlanService.PlannedActivity> concrete = windows.stream()
                .filter(window -> window != null && window.selectedSession() != null)
                .filter(window -> window.selectedSession().startAt() != null)
                .sorted(Comparator.comparing(window -> window.selectedSession().startAt()))
                .toList();
        if (concrete.size() < 2) return List.of();

        List<PlanValidationResult.Violation> result = new ArrayList<>();
        for (int i = 1; i < concrete.size(); i++) {
            ActivityPlanService.PlannedActivity previous = concrete.get(i - 1);
            ActivityPlanService.PlannedActivity next = concrete.get(i);
            ActivitySessionResponse from = previous.selectedSession();
            ActivitySessionResponse to = next.selectedSession();
            if (from.venueId() == null || to.venueId() == null || from.venueId().equals(to.venueId())) {
                continue;
            }
            if (!hasTravelEvidence(from.venueId(), to.venueId(), travelTimeEvidence)) {
                result.add(violation(
                        "MISSING_TRAVEL_EVIDENCE",
                        next.period(),
                        next.activity() == null ? null : next.activity().id(),
                        to.sessionId(),
                        "跨场地连续场次缺少真实路线时长证据",
                        "先对前后两个已暴露场次调用 get_travel_time，再重新 validate_plan"
                ));
            }
        }
        return result;
    }

    private List<PlanValidationResult.Violation> explicitConstraintViolations(
            List<ActivityPlanService.PlannedActivity> windows,
            BigDecimal maxBudget,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        List<PlanValidationResult.Violation> result = new ArrayList<>();
        List<ActivityPlanService.PlannedActivity> concrete = windows.stream()
                .filter(window -> window != null && window.selectedSession() != null)
                .filter(window -> window.selectedSession().startAt() != null
                        && window.selectedSession().endAt() != null)
                .sorted(Comparator.comparing(window -> window.selectedSession().startAt()))
                .toList();

        BigDecimal totalCost = concrete.stream()
                .map(window -> window.selectedSession().price())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (maxBudget != null && totalCost.compareTo(maxBudget) > 0) {
            result.add(violation(
                    "BUDGET_EXCEEDED", "", null, null,
                    "方案已知总价 " + totalCost + " 超过预算上限 " + maxBudget,
                    "替换价格较高的活动或场次后重新 validate_plan"
            ));
        }

        for (int i = 1; i < concrete.size(); i++) {
            ActivityPlanService.PlannedActivity previous = concrete.get(i - 1);
            ActivityPlanService.PlannedActivity next = concrete.get(i);
            ActivitySessionResponse from = previous.selectedSession();
            ActivitySessionResponse to = next.selectedSession();

            if (from.startAt().isBefore(to.endAt()) && from.endAt().isAfter(to.startAt())) {
                result.add(violation(
                        "TIME_CONFLICT",
                        next.period(),
                        next.activity() == null ? null : next.activity().id(),
                        to.sessionId(),
                        "连续场次时间发生重叠",
                        "更换冲突时段的活动或场次后重新 validate_plan"
                ));
                continue;
            }

            if (from.venueId() == null || to.venueId() == null || from.venueId().equals(to.venueId())) {
                continue;
            }
            TravelTimeEvidence route = travelTimeEvidence.stream()
                    .filter(Objects::nonNull)
                    .filter(evidence -> from.venueId().equals(evidence.fromVenueId())
                            && to.venueId().equals(evidence.toVenueId()))
                    .findFirst()
                    .orElse(null);
            if (route == null) continue;
            long availableMinutes = Duration.between(from.endAt(), to.startAt()).toMinutes();
            if (availableMinutes < route.durationMinutes()) {
                result.add(violation(
                        "TRAVEL_TIME_CONFLICT",
                        next.period(),
                        next.activity() == null ? null : next.activity().id(),
                        to.sessionId(),
                        "前后场次间隔 " + availableMinutes + " 分钟，小于真实路线时长 "
                                + route.durationMinutes() + " 分钟",
                        "更换下一场次、缩短跨场地距离或调整时段后重新 validate_plan"
                ));
            }
        }
        return result;
    }

    private boolean hasTravelEvidence(Long fromVenueId,
                                      Long toVenueId,
                                      List<TravelTimeEvidence> travelTimeEvidence) {
        return travelTimeEvidence.stream().anyMatch(evidence -> evidence != null
                && fromVenueId.equals(evidence.fromVenueId())
                && toVenueId.equals(evidence.toVenueId()));
    }

    private boolean matchesProposal(PlanCandidate candidate, PlanProposal proposal) {
        for (PlanProposal.Item proposed : proposal.items()) {
            boolean matched = candidate.items().stream().anyMatch(item ->
                    proposed.period().equals(item.period())
                            && proposed.activityId().equals(item.activity().id())
                            && (proposed.sessionId() == null
                                || sameSession(proposed.sessionId(), item.session())));
            if (!matched) return false;
        }
        return true;
    }

    private boolean sameSession(Long expectedSessionId, ActivitySessionResponse actual) {
        if (expectedSessionId == null) return actual == null;
        return actual != null && expectedSessionId.equals(actual.sessionId());
    }

    private PlanValidationResult.Violation violation(
            String code,
            String period,
            Long activityId,
            Long sessionId,
            String message,
            String repairHint
    ) {
        return new PlanValidationResult.Violation(
                code, period, activityId, sessionId, message, repairHint
        );
    }
}

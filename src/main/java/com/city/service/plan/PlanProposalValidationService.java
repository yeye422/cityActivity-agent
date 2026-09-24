package com.city.service.plan;

import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.PlanCandidate;
import com.city.model.TravelTimeEvidence;
import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanValidationResult;
import com.city.model.context.PlanningHorizon;
import com.city.service.evidence.PlanningEvidenceRegistry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** PlanningAgent 精确选择后的确定性硬约束校验；不枚举、不替 Agent 选活动或场次。 */
@Service
public class PlanProposalValidationService {

    public PlanValidationResult validate(
            PlanProposal proposal,
            PlanningEvidenceRegistry evidenceRegistry,
            PlanningHorizon horizon,
            BigDecimal maxBudget,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        if (proposal == null || proposal.items() == null || proposal.items().isEmpty()) {
            return PlanValidationResult.invalid(List.of(violation(
                    "EMPTY_PLAN", "", null, null,
                    "规划方案不能为空", "至少选择一个当前 Run 已暴露活动"
            )));
        }
        if (evidenceRegistry == null) {
            return PlanValidationResult.invalid(List.of(violation(
                    "MISSING_EVIDENCE", "", null, null,
                    "缺少本轮规划证据", "先完成服务器候选发现"
            )));
        }
        if (horizon == null || horizon.isEmpty()) {
            return PlanValidationResult.invalid(List.of(violation(
                    "MISSING_HORIZON", "", null, null,
                    "缺少可规划日期时间范围", "先完成服务器时间解析"
            )));
        }

        List<PlanValidationResult.Violation> violations = new ArrayList<>();
        Set<Long> usedActivityIds = new LinkedHashSet<>();
        List<PlanCandidate.Item> exactItems = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (PlanProposal.Item proposed : proposal.items()) {
            if (proposed == null || proposed.activityId() == null) continue;

            ActivityItem activity = evidenceRegistry.activity(proposed.activityId());
            if (activity == null) {
                violations.add(violation(
                        "ACTIVITY_NOT_EXPOSED", proposedRange(proposed),
                        proposed.activityId(), proposed.sessionId(),
                        "activityId 不属于当前 Run 已验证候选",
                        "从当前候选快照重新选择真实 activityId"
                ));
                continue;
            }
            if (!usedActivityIds.add(proposed.activityId())) {
                violations.add(violation(
                        "DUPLICATE_ACTIVITY", proposedRange(proposed),
                        proposed.activityId(), proposed.sessionId(),
                        "同一个活动不能在一个计划中重复安排",
                        "选择其他已暴露活动"
                ));
                continue;
            }

            List<ActivitySessionResponse> exposedSessions =
                    evidenceRegistry.sessionsForActivity(proposed.activityId());
            ActivitySessionResponse session = null;
            LocalDateTime startAt;
            LocalDateTime endAt;

            if (!exposedSessions.isEmpty()) {
                if (proposed.sessionId() == null) {
                    violations.add(violation(
                            "SESSION_REQUIRED", "", proposed.activityId(), null,
                            "该 activity 已暴露具体真实场次，必须明确选择 sessionId",
                            "从该 activity 的当前候选 sessions 中选择一个真实 OPEN sessionId"
                    ));
                    continue;
                }
                session = evidenceRegistry.session(proposed.activityId(), proposed.sessionId());
                if (session == null) {
                    violations.add(violation(
                            "SESSION_NOT_EXPOSED", "", proposed.activityId(), proposed.sessionId(),
                            "sessionId 不属于该 activity 的当前 Run 已验证场次",
                            "改选该 activity 当前候选快照中的真实 sessionId"
                    ));
                    continue;
                }
                if (!validSelectedSession(session, proposed.activityId())) {
                    violations.add(violation(
                            "SESSION_UNAVAILABLE", sessionRange(session),
                            proposed.activityId(), proposed.sessionId(),
                            "所选场次当前不可参加",
                            "选择状态 OPEN、仍有名额且时间有效的真实 sessionId"
                    ));
                    continue;
                }
                startAt = session.startAt();
                endAt = session.endAt();
                if (session.price() != null) totalCost = totalCost.add(session.price());
            } else {
                if (proposed.sessionId() != null) {
                    violations.add(violation(
                            "SESSION_NOT_EXPOSED", proposedRange(proposed),
                            proposed.activityId(), proposed.sessionId(),
                            "该 activity 当前没有暴露可选择的具体 session",
                            "移除 sessionId，并为无固定场次活动提供 plannedStartAt/plannedEndAt"
                    ));
                    continue;
                }
                startAt = proposed.plannedStartAt();
                endAt = proposed.plannedEndAt();
                if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
                    violations.add(violation(
                            "PLANNED_TIME_REQUIRED", proposedRange(proposed),
                            proposed.activityId(), null,
                            "无固定 session 的活动必须给出合法 plannedStartAt/plannedEndAt",
                            "在 PlanningHorizon 内为该活动安排明确开始和结束时间"
                    ));
                    continue;
                }
                if (activity.durationMinutes() != null && activity.durationMinutes() > 0
                        && Duration.between(startAt, endAt).toMinutes() < activity.durationMinutes()) {
                    violations.add(violation(
                            "ACTIVITY_DURATION_TOO_SHORT", rangeLabel(startAt, endAt),
                            proposed.activityId(), null,
                            "安排时长短于活动已知 durationMinutes=" + activity.durationMinutes(),
                            "延长 plannedEndAt 或重新选择活动"
                    ));
                    continue;
                }
            }

            if (!horizon.contains(startAt, endAt)) {
                violations.add(violation(
                        "OUTSIDE_PLANNING_HORIZON", rangeLabel(startAt, endAt),
                        proposed.activityId(), proposed.sessionId(),
                        "所选活动时间超出用户允许的规划日期时间范围",
                        "改选 horizon 内的 session，或为无固定场次活动调整 plannedStartAt/plannedEndAt"
                ));
                continue;
            }

            exactItems.add(new PlanCandidate.Item(activity, session, startAt, endAt));
        }

        if (!violations.isEmpty()) return PlanValidationResult.invalid(violations);
        if (exactItems.isEmpty()) {
            return PlanValidationResult.invalid(List.of(violation(
                    "EMPTY_PLAN", "", null, null,
                    "规划方案不能为空", "至少选择一个当前 Run 已暴露活动"
            )));
        }

        exactItems = exactItems.stream()
                .sorted(Comparator.comparing(PlanCandidate.Item::startAt))
                .toList();

        List<TravelTimeEvidence> safeTravel =
                travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);
        violations.addAll(missingTravelEvidence(exactItems, safeTravel));
        violations.addAll(explicitConstraintViolations(exactItems, totalCost, maxBudget, safeTravel));
        if (!violations.isEmpty()) return PlanValidationResult.invalid(violations);

        return PlanValidationResult.valid(new PlanCandidate(exactItems, totalCost));
    }

    private boolean validSelectedSession(ActivitySessionResponse session, Long activityId) {
        if (session == null || session.sessionId() == null) return false;
        if (session.activityId() != null && !session.activityId().equals(activityId)) return false;
        if (!"OPEN".equalsIgnoreCase(session.status())) return false;
        if (session.remainingSeats() != null && session.remainingSeats() <= 0) return false;
        return session.startAt() != null
                && session.endAt() != null
                && session.startAt().isBefore(session.endAt());
    }

    private List<PlanValidationResult.Violation> missingTravelEvidence(
            List<PlanCandidate.Item> items,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        List<PlanValidationResult.Violation> result = new ArrayList<>();
        for (int i = 1; i < items.size(); i++) {
            PlanCandidate.Item previous = items.get(i - 1);
            PlanCandidate.Item next = items.get(i);
            ActivitySessionResponse from = previous.session();
            ActivitySessionResponse to = next.session();
            if (from == null || to == null
                    || from.venueId() == null || to.venueId() == null
                    || from.venueId().equals(to.venueId())) {
                continue;
            }
            if (!hasTravelEvidence(from.venueId(), to.venueId(), travelTimeEvidence)) {
                String routeCall = "get_travel_time("
                        + "fromActivityId=" + previous.activity().id() + ", "
                        + "fromSessionId=" + from.sessionId() + ", "
                        + "toActivityId=" + next.activity().id() + ", "
                        + "toSessionId=" + to.sessionId() + ")";
                result.add(violation(
                        "MISSING_TRAVEL_EVIDENCE",
                        rangeLabel(previous.endAt(), next.startAt()),
                        next.activity().id(),
                        to.sessionId(),
                        "连续跨场地场次缺少真实路线时长证据",
                        "调用 " + routeCall + " 获取路线证据后重新提交"
                ));
            }
        }
        return result;
    }

    private List<PlanValidationResult.Violation> explicitConstraintViolations(
            List<PlanCandidate.Item> items,
            BigDecimal totalCost,
            BigDecimal maxBudget,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        List<PlanValidationResult.Violation> result = new ArrayList<>();
        if (maxBudget != null && totalCost.compareTo(maxBudget) > 0) {
            result.add(violation(
                    "BUDGET_EXCEEDED", "", null, null,
                    "方案已知总价 " + totalCost + " 超过预算上限 " + maxBudget,
                    "替换价格较高的活动或场次后重新提交"
            ));
        }

        for (int i = 1; i < items.size(); i++) {
            PlanCandidate.Item previous = items.get(i - 1);
            PlanCandidate.Item next = items.get(i);

            if (previous.startAt().isBefore(next.endAt())
                    && previous.endAt().isAfter(next.startAt())) {
                result.add(violation(
                        "TIME_CONFLICT",
                        rangeLabel(next.startAt(), next.endAt()),
                        next.activity().id(),
                        next.session() == null ? null : next.session().sessionId(),
                        "连续活动时间发生重叠",
                        "重新选择不重叠的 session 或调整无固定场次活动时间"
                ));
                continue;
            }

            ActivitySessionResponse from = previous.session();
            ActivitySessionResponse to = next.session();
            if (from == null || to == null
                    || from.venueId() == null || to.venueId() == null
                    || from.venueId().equals(to.venueId())) {
                continue;
            }
            TravelTimeEvidence route = travelTimeEvidence.stream()
                    .filter(Objects::nonNull)
                    .filter(evidence -> from.venueId().equals(evidence.fromVenueId())
                            && to.venueId().equals(evidence.toVenueId()))
                    .findFirst()
                    .orElse(null);
            if (route == null) continue;
            long availableMinutes = Duration.between(previous.endAt(), next.startAt()).toMinutes();
            if (availableMinutes < route.durationMinutes()) {
                result.add(violation(
                        "TRAVEL_TIME_CONFLICT",
                        rangeLabel(previous.endAt(), next.startAt()),
                        next.activity().id(),
                        to.sessionId(),
                        "前后活动间隔 " + availableMinutes + " 分钟，小于真实路线时长 "
                                + route.durationMinutes() + " 分钟",
                        "更换下一场次、缩短跨场地距离或调整活动后重新提交"
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

    private String proposedRange(PlanProposal.Item item) {
        return item == null ? "" : rangeLabel(item.plannedStartAt(), item.plannedEndAt());
    }

    private String sessionRange(ActivitySessionResponse session) {
        return session == null ? "" : rangeLabel(session.startAt(), session.endAt());
    }

    private String rangeLabel(LocalDateTime startAt, LocalDateTime endAt) {
        return startAt == null || endAt == null ? "" : startAt + "/" + endAt;
    }

    private PlanValidationResult.Violation violation(
            String code,
            String timeRange,
            Long activityId,
            Long sessionId,
            String message,
            String repairHint
    ) {
        return new PlanValidationResult.Violation(
                code, timeRange, activityId, sessionId, message, repairHint
        );
    }
}

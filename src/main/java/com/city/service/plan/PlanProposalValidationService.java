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
import java.util.Objects;
import java.util.Set;

/**
 * 对 PlanningAgent 提交的精确 period/activity/session 引用执行确定性硬约束校验。
 *
 * <p>Java 不枚举候选组合，也不替 Agent 选择 session。Agent 对存在具体场次的活动必须明确提交
 * 当前 Run 已暴露的 sessionId；Java 只负责 Evidence 绑定、可用性、预算、时间和路线校验。</p>
 */
@Service
public class PlanProposalValidationService {

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
                    "缺少本轮规划证据", "先完成服务器候选发现"
            )));
        }

        List<PlanValidationResult.Violation> violations = new ArrayList<>();
        Set<Long> usedActivityIds = new LinkedHashSet<>();
        List<PlanCandidate.Item> exactItems = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (PlanProposal.Item proposed : proposal.items()) {
            if (proposed == null) continue;
            if (!evidenceRegistry.hasPeriod(proposed.period())) {
                violations.add(violation(
                        "UNKNOWN_PERIOD", proposed.period(), proposed.activityId(), proposed.sessionId(),
                        "该 period 未由当前规划候选暴露", "从候选快照中的真实 period 重新选择"
                ));
                continue;
            }

            ActivityItem activity = evidenceRegistry.activity(proposed.period(), proposed.activityId());
            if (activity == null) {
                violations.add(violation(
                        "ACTIVITY_NOT_EXPOSED", proposed.period(), proposed.activityId(), proposed.sessionId(),
                        "activityId 不属于该 period 的当前 Run 候选",
                        "改选该 period 候选快照中的真实 activityId"
                ));
                continue;
            }
            if (!usedActivityIds.add(proposed.activityId())) {
                violations.add(violation(
                        "DUPLICATE_ACTIVITY", proposed.period(), proposed.activityId(), proposed.sessionId(),
                        "同一个活动不能在一个计划中重复安排", "为该 period 选择其他活动"
                ));
                continue;
            }

            ActivityPlanService.PlannedActivity sourceWindow = evidenceRegistry.windows().stream()
                    .filter(window -> proposed.period().equals(window.period()))
                    .findFirst()
                    .orElseThrow();

            boolean concreteSessionRequired = !sourceWindow.sessionsByActivityId().isEmpty();
            ActivitySessionResponse session = null;
            if (concreteSessionRequired) {
                if (proposed.sessionId() == null) {
                    violations.add(violation(
                            "SESSION_REQUIRED", proposed.period(), proposed.activityId(), null,
                            "该规划窗口已有具体真实场次，Agent 必须明确选择 sessionId",
                            "从该 activity 的候选场次中选择一个真实 OPEN sessionId"
                    ));
                    continue;
                }
                session = evidenceRegistry.session(
                        proposed.period(), proposed.activityId(), proposed.sessionId());
                if (session == null) {
                    violations.add(violation(
                            "SESSION_NOT_EXPOSED", proposed.period(), proposed.activityId(), proposed.sessionId(),
                            "sessionId 不属于该 activity 在该 period 的当前 Run 已验证场次",
                            "改选候选快照中该 activity 对应的真实 sessionId"
                    ));
                    continue;
                }
                if (!validSelectedSession(session, proposed.activityId())) {
                    violations.add(violation(
                            "SESSION_UNAVAILABLE", proposed.period(), proposed.activityId(), proposed.sessionId(),
                            "所选场次当前不可参加",
                            "选择状态 OPEN、仍有名额且时间有效的真实 sessionId"
                    ));
                    continue;
                }
                if (session.price() != null) {
                    totalCost = totalCost.add(session.price());
                }
            } else if (proposed.sessionId() != null) {
                violations.add(violation(
                        "UNEXPECTED_SESSION", proposed.period(), proposed.activityId(), proposed.sessionId(),
                        "该窗口没有暴露具体场次，不应提交 sessionId",
                        "移除 sessionId，仅保留 period + activityId"
                ));
                continue;
            }

            exactItems.add(new PlanCandidate.Item(proposed.period(), activity, session));
        }

        if (!violations.isEmpty()) {
            return PlanValidationResult.invalid(violations);
        }
        if (exactItems.isEmpty()) {
            return PlanValidationResult.invalid(List.of(violation(
                    "EMPTY_PLAN", "", null, null,
                    "规划方案不能为空", "至少选择一个已检索到的活动"
            )));
        }

        List<TravelTimeEvidence> safeTravel =
                travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);
        violations.addAll(missingTravelEvidence(exactItems, safeTravel));
        violations.addAll(explicitConstraintViolations(exactItems, totalCost, maxBudget, safeTravel));
        if (!violations.isEmpty()) {
            return PlanValidationResult.invalid(violations);
        }

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
        List<PlanCandidate.Item> concrete = concreteItems(items);
        if (concrete.size() < 2) return List.of();

        List<PlanValidationResult.Violation> result = new ArrayList<>();
        for (int i = 1; i < concrete.size(); i++) {
            PlanCandidate.Item previous = concrete.get(i - 1);
            PlanCandidate.Item next = concrete.get(i);
            ActivitySessionResponse from = previous.session();
            ActivitySessionResponse to = next.session();
            if (from.venueId() == null || to.venueId() == null || from.venueId().equals(to.venueId())) {
                continue;
            }
            if (!hasTravelEvidence(from.venueId(), to.venueId(), travelTimeEvidence)) {
                result.add(violation(
                        "MISSING_TRAVEL_EVIDENCE",
                        next.period(),
                        next.activity().id(),
                        to.sessionId(),
                        "跨场地连续场次缺少真实路线时长证据",
                        "服务器补齐路线证据后重新 validate_plan"
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

        List<PlanCandidate.Item> concrete = concreteItems(items);
        for (int i = 1; i < concrete.size(); i++) {
            PlanCandidate.Item previous = concrete.get(i - 1);
            PlanCandidate.Item next = concrete.get(i);
            ActivitySessionResponse from = previous.session();
            ActivitySessionResponse to = next.session();

            if (from.startAt().isBefore(to.endAt()) && from.endAt().isAfter(to.startAt())) {
                result.add(violation(
                        "TIME_CONFLICT",
                        next.period(),
                        next.activity().id(),
                        to.sessionId(),
                        "连续场次时间发生重叠",
                        "更换冲突 period 的 activity 或 session 后重新提交"
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
                        next.activity().id(),
                        to.sessionId(),
                        "前后场次间隔 " + availableMinutes + " 分钟，小于真实路线时长 "
                                + route.durationMinutes() + " 分钟",
                        "更换下一场次、缩短跨场地距离或调整活动后重新提交"
                ));
            }
        }
        return result;
    }

    private List<PlanCandidate.Item> concreteItems(List<PlanCandidate.Item> items) {
        return items.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.session() != null)
                .sorted(Comparator.comparing(item -> item.session().startAt()))
                .toList();
    }

    private boolean hasTravelEvidence(Long fromVenueId,
                                      Long toVenueId,
                                      List<TravelTimeEvidence> travelTimeEvidence) {
        return travelTimeEvidence.stream().anyMatch(evidence -> evidence != null
                && fromVenueId.equals(evidence.fromVenueId())
                && toVenueId.equals(evidence.toVenueId()));
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

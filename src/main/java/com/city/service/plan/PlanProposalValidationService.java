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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
            boolean concreteSessionRequired = !sourceWindow.sessionsByActivityId().isEmpty();
            ActivitySessionResponse session = null;
            if (proposed.sessionId() != null) {
                session = evidenceRegistry.session(
                        proposed.period(), proposed.activityId(), proposed.sessionId());
                if (session == null) {
                    violations.add(violation(
                            "SESSION_NOT_EXPOSED", proposed.period(), proposed.activityId(), proposed.sessionId(),
                            "sessionId 不属于该活动在该时段的已验证场次", "改选 Tool 返回的 OPEN 场次"
                    ));
                    continue;
                }
            } else if (concreteSessionRequired) {
                violations.add(violation(
                        "SESSION_REQUIRED", proposed.period(), proposed.activityId(), null,
                        "该规划请求已有具体日期，必须选择真实可参加场次", "为该活动选择一个 Tool 返回的 sessionId"
                ));
                continue;
            }

            Map<Long, List<ActivitySessionResponse>> sessions = session == null
                    ? Map.of()
                    : Map.of(activity.id(), List.of(session));
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

        List<PlanCandidate> legal = planningSolver.solve(
                restrictedWindows,
                maxBudget,
                travelTimeEvidence == null ? List.of() : travelTimeEvidence
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

    private boolean matchesProposal(PlanCandidate candidate, PlanProposal proposal) {
        for (PlanProposal.Item proposed : proposal.items()) {
            boolean matched = candidate.items().stream().anyMatch(item ->
                    proposed.period().equals(item.period())
                            && proposed.activityId().equals(item.activity().id())
                            && sameSession(proposed.sessionId(), item.session()));
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

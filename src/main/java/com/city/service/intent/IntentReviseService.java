package com.city.service.intent;

import com.city.enums.ConstraintOperationType;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.model.ConstraintOperation;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TemporalMutation;
import com.city.model.TimeConstraint;
import com.city.service.time.TemporalValidator;
import com.city.service.time.TimeMutationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图后处理服务。
 * LLM 意图识别可能误判，Orchestrator 在路由前用历史 SessionState 做二次矫正。
 */
@Service
public class IntentReviseService {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.4;

    private final TimeMutationService timeMutationService;
    private final TemporalValidator temporalValidator;

    public IntentReviseService(TimeMutationService timeMutationService, TemporalValidator temporalValidator) {
        this.timeMutationService = timeMutationService;
        this.temporalValidator = temporalValidator;
    }

    public IntentResult revise(SessionState state, IntentResult result, String userInput) {
        IntentResult safeResult = result == null ? IntentResult.clarify(SlotBundle.empty()) : result;
        safeResult = bridgeTemporalMutation(state, safeResult);

        if (safeResult.intent() == Intent.HEALTH_RISK || containsSafetyRiskKeyword(userInput)) {
            return revised(Intent.HEALTH_RISK, safeResult);
        }

        if (state != null
                && state.phase() == SessionPhase.CLARIFY
                && state.currentIntent() == Intent.ACTIVITY_PLAN
                && (safeResult.intent() == Intent.MEAL_RECOMMENDATION || safeResult.intent() == Intent.CLARIFY_NEEDED)) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        if (safeResult.intent() == Intent.MEAL_ADJUST && !hasLastRecommendations(state)) {
            return revised(Intent.MEAL_RECOMMENDATION, safeResult);
        }

        if (containsActivityPlanKeyword(userInput)
                && safeResult.intent() != Intent.MEAL_ADJUST
                && safeResult.intent() != Intent.ACTIVITY_PLAN) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        if (safeResult.intent() == Intent.MEAL_RECOMMENDATION && safeResult.confidence() < LOW_CONFIDENCE_THRESHOLD) {
            return revised(Intent.CLARIFY_NEEDED, safeResult);
        }

        return safeResult;
    }

    /**
     * 兼容现有 Orchestrator：先在这里按 KEEP/SET/CLEAR 合并出完整 TimeConstraint，
     * 再转换为旧的 time SET/CLEAR operation。这样无需一次性重写编排主链路。
     */
    private IntentResult bridgeTemporalMutation(SessionState state, IntentResult result) {
        TemporalMutation temporal = result.temporal();
        if (temporal == null || !temporal.changesAnything() || !temporalValidator.isValid(temporal)) {
            return result;
        }

        TimeConstraint historical = state == null ? TimeConstraint.empty() : state.timeConstraint();
        TimeConstraint merged = timeMutationService.apply(historical, temporal);

        List<ConstraintOperation> operations = new ArrayList<>();
        if (result.operations() != null) {
            result.operations().stream()
                    .filter(operation -> operation != null && !"time".equals(operation.field()))
                    .forEach(operations::add);
        }

        if (merged.hasConstraint()) {
            operations.add(new ConstraintOperation(
                    "time", ConstraintOperationType.SET, List.of(), toParserExpression(temporal.raw(), merged)));
        } else {
            operations.add(new ConstraintOperation(
                    "time", ConstraintOperationType.CLEAR, List.of(), temporal.raw() == null ? "" : temporal.raw()));
        }

        return new IntentResult(
                result.intent(), safeSlots(result), result.confidence(), List.copyOf(operations), temporal);
    }

    /**
     * 将完整绝对约束转换为现有 TimeExpressionParser 能稳定识别的表达式。
     * 前缀保留用户原始时间片段，使 Trace 与既有 expectedTime 回归标注仍可直接比对。
     */
    private String toParserExpression(String originalRaw, TimeConstraint time) {
        StringBuilder value = new StringBuilder();
        if (originalRaw != null && !originalRaw.isBlank()) {
            value.append(originalRaw.trim()).append(' ');
        }
        if (time.hasDate()) {
            if (time.dateStart().equals(time.dateEnd())) {
                value.append(time.dateStart().getYear()).append("年")
                        .append(time.dateStart().getMonthValue()).append("月")
                        .append(time.dateStart().getDayOfMonth()).append("日");
            } else {
                value.append(time.dateStart().getYear()).append("年")
                        .append(time.dateStart().getMonthValue()).append("月")
                        .append(time.dateStart().getDayOfMonth()).append("日到")
                        .append(time.dateEnd().getMonthValue()).append("月")
                        .append(time.dateEnd().getDayOfMonth()).append("日");
            }
        }
        if (time.hasTime()) {
            if (!value.isEmpty() && value.charAt(value.length() - 1) != ' ') value.append(' ');
            value.append(time.startTime()).append("到").append(time.endTime());
        }
        return value.toString().trim();
    }

    private boolean hasLastRecommendations(SessionState state) {
        return state != null && state.lastRecommendedActivityIds() != null && !state.lastRecommendedActivityIds().isEmpty();
    }

    private SlotBundle safeSlots(IntentResult result) {
        return result.slots() == null ? SlotBundle.empty() : result.slots();
    }

    private IntentResult revised(Intent intent, IntentResult result) {
        return new IntentResult(
                intent,
                safeSlots(result),
                result.confidence(),
                result.operations() == null ? List.of() : result.operations(),
                result.temporal() == null ? TemporalMutation.keep() : result.temporal()
        );
    }

    private boolean containsSafetyRiskKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        return containsAny(userInput, "深夜独自", "凌晨一个人", "偏远", "无人区", "危险活动", "极端天气", "暴雨", "台风");
    }

    private boolean containsActivityPlanKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        return containsAny(userInput, "半天", "一天", "一日", "活动规划", "行程", "安排一下", "周末安排");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}

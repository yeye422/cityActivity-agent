package com.city.service.worker;

import com.city.enums.SourceMode;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanningExecutionResult;
import com.city.model.agent.PlanningResult;
import com.city.service.plan.ActivityPlanService;
import com.city.service.plan.PlanningSolver;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多窗口候选发现、证据收集与确定性方案求解 Worker，不负责写会话状态。
 */
public final class PlanningWorker {
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private final ActivityPlanService activityPlanService;
    private final PlanningSolver planningSolver;

    public PlanningWorker(ActivityPlanService activityPlanService) {
        this(activityPlanService, new PlanningSolver());
    }

    PlanningWorker(ActivityPlanService activityPlanService, PlanningSolver planningSolver) {
        this.activityPlanService = activityPlanService;
        this.planningSolver = planningSolver;
    }

    public List<String> resolveWindows(SlotBundle slots, TimeConstraint timeConstraint) {
        return activityPlanService.resolveActivityTimes(slots, timeConstraint);
    }

    /** 兼容窗口级规划结果；后续调用方优先使用 planAndSolve。 */
    public PlanningResult plan(SourceMode sourceMode,
                               Long userId,
                               SlotBundle slots,
                               SlotBundle excludedSlots,
                               List<String> windows,
                               TimeConstraint timeConstraint,
                               WeatherRecommendationContext weather) {
        return activityPlanService.planWithEvidence(
                sourceMode, userId, slots, excludedSlots, windows, timeConstraint, weather);
    }

    /**
     * 先构造窗口级合法候选和证据，再由 Java PlanningSolver 生成多个满足硬约束的 PlanCandidate。
     */
    public PlanningExecutionResult planAndSolve(SourceMode sourceMode,
                                                Long userId,
                                                SlotBundle slots,
                                                SlotBundle excludedSlots,
                                                List<String> windows,
                                                TimeConstraint timeConstraint,
                                                WeatherRecommendationContext weather) {
        PlanningResult planning = plan(
                sourceMode, userId, slots, excludedSlots, windows, timeConstraint, weather);
        return new PlanningExecutionResult(
                planning,
                planningSolver.solve(planning.plans(), explicitMaxBudget(slots))
        );
    }

    /**
     * 只对单一且明确的预算标签建立硬上限；多预算/无法识别的自然语言值保持为 null，避免错误收紧。
     */
    BigDecimal explicitMaxBudget(SlotBundle slots) {
        if (slots == null || slots.budget() == null || slots.budget().size() != 1) return null;
        String value = slots.budget().getFirst();
        if (value == null || value.isBlank()) return null;
        if (value.contains("免费")) return BigDecimal.ZERO;
        Matcher matcher = NUMBER.matcher(value);
        if (!matcher.find()) return null;
        try {
            return new BigDecimal(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

package com.city.service.worker;

import com.city.enums.SourceMode;
import com.city.model.PlanCandidate;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanningExecutionResult;
import com.city.model.agent.PlanningResult;
import com.city.service.plan.ActivityPlanService;
import com.city.service.plan.PlanningSolver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * 线上兼容入口：先执行候选发现和 Solver，再只向下游暴露 Solver 排名第一的合法方案。
     * 保留 PlanningResult 类型，避免当前 Supervisor 在 Workflow 拆分前再次承担大范围改动。
     */
    public PlanningResult plan(SourceMode sourceMode,
                               Long userId,
                               SlotBundle slots,
                               SlotBundle excludedSlots,
                               List<String> windows,
                               TimeConstraint timeConstraint,
                               WeatherRecommendationContext weather) {
        PlanningExecutionResult execution = planAndSolve(
                sourceMode, userId, slots, excludedSlots, windows, timeConstraint, weather);
        return new PlanningResult(
                responsePlans(execution),
                execution.planning().agentResult()
        );
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
        PlanningResult planning = discover(
                sourceMode, userId, slots, excludedSlots, windows, timeConstraint, weather);
        return new PlanningExecutionResult(
                planning,
                planningSolver.solve(planning.plans(), explicitMaxBudget(slots))
        );
    }

    private PlanningResult discover(SourceMode sourceMode,
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
     * 过渡期响应边界：只把 Solver 排名第一的合法 PlanCandidate 暴露给旧 PlanResponseAgent。
     * 每个原始窗口仍保留；Solver 选择的窗口只暴露一个 activity/session，未选择窗口暴露为空候选，
     * 因此响应模型不能再跨窗口重新拼接未通过硬约束校验的活动。
     */
    public List<ActivityPlanService.PlannedActivity> responsePlans(PlanningExecutionResult execution) {
        if (execution == null || execution.planning() == null
                || execution.planCandidates() == null || execution.planCandidates().isEmpty()) {
            return List.of();
        }

        PlanCandidate selected = execution.planCandidates().getFirst();
        List<ActivityPlanService.PlannedActivity> result = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity source : execution.planning().plans()) {
            if (source == null) continue;
            PlanCandidate.Item item = selected.items().stream()
                    .filter(candidate -> source.period().equals(candidate.period()))
                    .findFirst()
                    .orElse(null);
            if (item == null) {
                result.add(new ActivityPlanService.PlannedActivity(
                        source.period(), null, source.querySlots(), List.of(), Map.of(), null));
                continue;
            }

            Map<Long, List<com.city.model.ActivitySessionResponse>> sessions = item.session() == null
                    ? Map.of()
                    : Map.of(item.activity().id(), List.of(item.session()));
            result.add(new ActivityPlanService.PlannedActivity(
                    source.period(),
                    item.activity(),
                    source.querySlots(),
                    List.of(item.activity()),
                    sessions,
                    item.session()));
        }
        return List.copyOf(result);
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

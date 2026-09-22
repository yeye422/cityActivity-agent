package com.city.service.workflow;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.SlotMutation;
import com.city.model.context.SemanticContext;
import com.city.service.clarify.ClarifyRuleService;
import com.city.service.context.SemanticContextBuilder;
import com.city.service.plan.TimeWindowResolver;
import com.city.service.slot.SlotMutationService;
import com.city.service.worker.PlanningWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 多时段规划 Workflow 的确定性准备阶段，不负责检索、Solver、响应生成或状态持久化。 */
@Service
public final class PlanningWorkflow {
    private final SlotMutationService slotMutationService;
    private final ClarifyRuleService clarifyRuleService;
    private final TimeWindowResolver timeWindowResolver;

    @Autowired
    public PlanningWorkflow(SlotMutationService slotMutationService,
                            ClarifyRuleService clarifyRuleService,
                            TimeWindowResolver timeWindowResolver) {
        this.slotMutationService = Objects.requireNonNull(slotMutationService, "slotMutationService");
        this.clarifyRuleService = Objects.requireNonNull(clarifyRuleService, "clarifyRuleService");
        this.timeWindowResolver = Objects.requireNonNull(timeWindowResolver, "timeWindowResolver");
    }

    /** 旧 Supervisor 迁移兼容构造器；最终薄 Supervisor 替换后删除。 */
    @Deprecated
    public PlanningWorkflow(SlotMutationService slotMutationService,
                            ClarifyRuleService clarifyRuleService,
                            PlanningWorker ignoredPlanningWorker) {
        this(slotMutationService, clarifyRuleService, new TimeWindowResolver());
    }

    public Preparation prepare(SessionState state, IntentResult intent) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(intent, "intent");
        SlotMutation mutation = slotMutationService.apply(
                intent.operations(), state.slots(), state.excludedSlots(), state.unconstrainedSlots());
        SessionState workingState = state.withIntent(Intent.ACTIVITY_PLAN)
                .withSlots(mutation.included())
                .withExcludedSlots(mutation.excluded())
                .withUnconstrainedSlots(mutation.unconstrained());
        ClarifyField missing = firstMissing(workingState);
        if (missing != null) {
            return new Preparation(workingState, mutation, missing, List.of());
        }

        SlotBundle merged = mutation.included();
        SlotBundle planSlots = new SlotBundle(
                merged.city(), merged.location(), merged.experienceGoal(), merged.companion(), merged.budget(),
                merged.activityType(), merged.style(), merged.duration(), merged.feature());
        workingState = workingState.withSlots(planSlots)
                .withPendingClarifyField(null)
                .withPhase(SessionPhase.PLAN);
        List<String> windows = timeWindowResolver.resolve(planSlots, workingState.timeConstraint());
        return new Preparation(workingState, mutation, null, windows);
    }

    private ClarifyField firstMissing(SessionState state) {
        List<ClarifyField> missing = clarifyRuleService.missingRequiredFields(
                Intent.ACTIVITY_PLAN, state.slots(), state.timeConstraint());
        return missing.isEmpty() ? null : missing.getFirst();
    }

    public record Preparation(SessionState state, SlotMutation mutation, ClarifyField missingField, List<String> windows) {
        public Preparation {
            windows = windows == null ? List.of() : List.copyOf(windows);
        }

        public SemanticContext semanticContext() {
            return new SemanticContextBuilder().build(state);
        }
    }
}

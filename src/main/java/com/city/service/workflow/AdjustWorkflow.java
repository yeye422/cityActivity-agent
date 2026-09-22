package com.city.service.workflow;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotMutation;
import com.city.service.clarify.ClarifyRuleService;
import com.city.service.slot.SlotMutationService;

import java.util.List;
import java.util.Objects;

/**
 * 条件调整 Workflow 的确定性准备阶段。
 * 负责 SET/ADD/REMOVE/CLEAR Patch 应用和推荐必需条件澄清，不直接执行检索或写会话状态。
 */
public final class AdjustWorkflow {
    private final SlotMutationService slotMutationService;
    private final ClarifyRuleService clarifyRuleService;

    public AdjustWorkflow(SlotMutationService slotMutationService,
                          ClarifyRuleService clarifyRuleService) {
        this.slotMutationService = Objects.requireNonNull(slotMutationService, "slotMutationService");
        this.clarifyRuleService = Objects.requireNonNull(clarifyRuleService, "clarifyRuleService");
    }

    public Preparation prepare(SessionState state, IntentResult intent) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(intent, "intent");
        SlotMutation mutation = slotMutationService.apply(
                intent.operations(), state.slots(), state.excludedSlots(), state.unconstrainedSlots());
        SessionState workingState = state.withIntent(Intent.ACTIVITY_ADJUST)
                .withSlots(mutation.included())
                .withExcludedSlots(mutation.excluded())
                .withUnconstrainedSlots(mutation.unconstrained());
        ClarifyField missing = firstMissing(workingState);
        if (missing == null) {
            workingState = workingState.withPendingClarifyField(null).withPhase(SessionPhase.RECOMMEND);
        }
        return new Preparation(workingState, mutation, missing);
    }

    private ClarifyField firstMissing(SessionState state) {
        List<ClarifyField> missing = clarifyRuleService.missingRequiredFields(
                Intent.ACTIVITY_RECOMMENDATION, state.slots(), state.timeConstraint());
        return missing.isEmpty() ? null : missing.getFirst();
    }

    public record Preparation(
            SessionState state,
            SlotMutation mutation,
            ClarifyField missingField
    ) {}
}

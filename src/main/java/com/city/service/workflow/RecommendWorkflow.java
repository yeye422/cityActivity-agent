package com.city.service.workflow;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotMutation;
import com.city.model.context.SemanticContext;
import com.city.service.clarify.ClarifyRuleService;
import com.city.service.context.SemanticContextBuilder;
import com.city.service.slot.SlotMutationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 普通推荐 Workflow 的确定性准备阶段，不直接持久化 SessionState。 */
@Service
public final class RecommendWorkflow {
    private final SlotMutationService slotMutationService;
    private final ClarifyRuleService clarifyRuleService;

    public RecommendWorkflow(SlotMutationService slotMutationService,
                             ClarifyRuleService clarifyRuleService) {
        this.slotMutationService = Objects.requireNonNull(slotMutationService, "slotMutationService");
        this.clarifyRuleService = Objects.requireNonNull(clarifyRuleService, "clarifyRuleService");
    }

    public Preparation prepare(SessionState state, IntentResult intent) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(intent, "intent");
        SlotMutation mutation = slotMutationService.apply(
                intent.operations(), state.slots(), state.excludedSlots(), state.unconstrainedSlots());
        SessionState workingState = state.withIntent(Intent.ACTIVITY_RECOMMENDATION)
                .withSlots(mutation.included())
                .withExcludedSlots(mutation.excluded())
                .withUnconstrainedSlots(mutation.unconstrained());
        ClarifyField missing = firstMissing(Intent.ACTIVITY_RECOMMENDATION, workingState);
        if (missing == null) {
            workingState = workingState.withPendingClarifyField(null).withPhase(SessionPhase.RECOMMEND);
        }
        return new Preparation(workingState, mutation, missing);
    }

    private ClarifyField firstMissing(Intent intent, SessionState state) {
        List<ClarifyField> missing = clarifyRuleService.missingRequiredFields(
                intent, state.slots(), state.timeConstraint());
        return missing.isEmpty() ? null : missing.getFirst();
    }

    public record Preparation(SessionState state, SlotMutation mutation, ClarifyField missingField) {
        public SemanticContext semanticContext() {
            return new SemanticContextBuilder().build(state);
        }
    }
}

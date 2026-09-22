package com.city.tool;

import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanValidationResult;
import com.city.model.context.PlanningToolContext;
import com.city.service.plan.PlanProposalValidationService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** PlanningAgent 的确定性方案校验 Tool；所有事实均从本轮 PlanningEvidenceRegistry 绑定。 */
@Component
public class PlanValidationTool {

    private final PlanProposalValidationService validationService;

    public PlanValidationTool(PlanProposalValidationService validationService) {
        this.validationService = Objects.requireNonNull(validationService, "validationService");
    }

    @Tool(
            name = "validate_plan",
            description = "Validate a proposed multi-window plan against verified activity/session evidence, budget, time and travel constraints."
    )
    public PlanValidationResult validate(
            @ToolParam(
                    name = "proposal",
                    description = "Plan containing only period, activityId and optional sessionId returned by discover_plan_candidates"
            ) PlanProposal proposal,
            PlanningToolContext planningContext
    ) {
        Objects.requireNonNull(planningContext, "planningContext");
        return validationService.validate(
                proposal,
                planningContext.evidenceRegistry(),
                planningContext.maxBudget(),
                planningContext.travelTimeEvidence()
        );
    }
}

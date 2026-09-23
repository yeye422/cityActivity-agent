package com.city.tool;

import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanValidationResult;
import com.city.model.context.PlanningToolContext;
import com.city.service.plan.PlanProposalValidationService;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** PlanningAgent 的确定性方案校验 Tool；所有事实均从本轮 PlanningEvidenceRegistry 绑定。 */
@Component
public class PlanValidationTool {

    private final PlanProposalValidationService validationService;
    private final AgentTraceService traceService;

    /** 保留纯单测构造入口。 */
    public PlanValidationTool(PlanProposalValidationService validationService) {
        this(validationService, null);
    }

    @Autowired
    public PlanValidationTool(PlanProposalValidationService validationService,
                              AgentTraceService traceService) {
        this.validationService = Objects.requireNonNull(validationService, "validationService");
        this.traceService = traceService;
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
        planningContext.evidenceRegistry().beginValidation();
        boolean repairing = planningContext.notebook().beginValidation(proposal);
        int validationCall = planningContext.evidenceRegistry().validationCalls();
        if (traceService != null) {
            traceService.recordEventForTrace(
                    planningContext.verifiedRequestContext().traceId(),
                    "PLAN_VALIDATION_TOOL_CALLED",
                    "TOOL",
                    Map.of("validationCall", validationCall, "proposal", proposal),
                    null
            );
        }
        if (traceService != null) {
            traceService.recordEventForTrace(
                    planningContext.verifiedRequestContext().traceId(),
                    "PLAN_PROPOSED",
                    "AGENT",
                    Map.of("repairing", repairing, "notebook", planningContext.notebook().snapshot()),
                    proposal
            );
            if (repairing) {
                traceService.recordEventForTrace(
                        planningContext.verifiedRequestContext().traceId(),
                        "PLAN_REPAIRED",
                        "AGENT",
                        planningContext.notebook().snapshot(),
                        proposal
                );
            }
        }
        PlanValidationResult result = validationService.validate(
                proposal,
                planningContext.evidenceRegistry(),
                planningContext.maxBudget(),
                planningContext.allTravelTimeEvidence()
        );
        planningContext.notebook().completeValidation(result);
        if (traceService != null) {
            traceService.recordEventForTrace(
                    planningContext.verifiedRequestContext().traceId(),
                    result.valid() ? "PLAN_VALIDATION_PASSED" : "PLAN_VALIDATION_FAILED",
                    "TOOL",
                    Map.of(
                            "validationCall", validationCall,
                            "travelEvidenceCount", planningContext.allTravelTimeEvidence().size(),
                            "notebook", planningContext.notebook().snapshot()
                    ),
                    result
            );
        }
        if (traceService != null && result.valid()) {
            traceService.recordEventForTrace(
                    planningContext.verifiedRequestContext().traceId(),
                    "PLAN_VALIDATED",
                    "AGENT",
                    planningContext.notebook().snapshot(),
                    result
            );
        }
        return result;
    }
}

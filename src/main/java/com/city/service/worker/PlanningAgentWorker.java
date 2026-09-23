package com.city.service.worker;

import com.city.agent.builder.PlanningAgentBuilder;
import com.city.model.TravelTimeEvidence;
import com.city.model.agent.PlanNotebook;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.agent.PlanningDecision;
import com.city.model.agent.PlanValidationResult;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.evidence.DecisionEvidenceValidator;
import com.city.service.evidence.PlanningEvidenceRegistry;
import com.city.service.evidence.RunEvidenceStore;
import com.city.service.plan.PlanProposalValidationService;
import com.city.service.plan.PlanningConstraintParser;
import com.city.service.trace.AgentTraceService;
import com.city.tool.PlanValidationTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * PlanningAgent 的执行边界。不写 SessionState；最终方案必须同时通过 Solver 与当前 Run Evidence 门禁。
 */
@Component
public final class PlanningAgentWorker {

    private final PlanningAgentBuilder agentBuilder;
    private final PlanningConstraintParser constraintParser;
    private final PlanProposalValidationService validationService;
    private final PlanValidationTool validationTool;
    private final DecisionEvidenceValidator evidenceValidator = new DecisionEvidenceValidator();
    private final AgentTraceService traceService;

    /** 保留现有纯单测构造入口。 */
    public PlanningAgentWorker(
            PlanningAgentBuilder agentBuilder,
            PlanningConstraintParser constraintParser,
            PlanProposalValidationService validationService,
            AgentTraceService traceService
    ) {
        this(
                agentBuilder,
                constraintParser,
                validationService,
                new PlanValidationTool(validationService, traceService),
                traceService
        );
    }

    @Autowired
    public PlanningAgentWorker(
            PlanningAgentBuilder agentBuilder,
            PlanningConstraintParser constraintParser,
            PlanProposalValidationService validationService,
            PlanValidationTool validationTool,
            AgentTraceService traceService
    ) {
        this.agentBuilder = Objects.requireNonNull(agentBuilder, "agentBuilder");
        this.constraintParser = Objects.requireNonNull(constraintParser, "constraintParser");
        this.validationService = Objects.requireNonNull(validationService, "validationService");
        this.validationTool = Objects.requireNonNull(validationTool, "validationTool");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
    }

    public PlanningAgentExecutionResult execute(
            String userInput,
            VerifiedRequestContext verifiedContext,
            List<String> windows,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        Objects.requireNonNull(verifiedContext, "verifiedContext");
        List<String> safeWindows = windows == null ? List.of() : List.copyOf(windows);
        List<TravelTimeEvidence> safeTravel = travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);
        RunEvidenceStore evidenceStore = new RunEvidenceStore(verifiedContext.traceId());
        safeTravel.forEach(evidenceStore::recordTravelEvidence);
        PlanningEvidenceRegistry evidenceRegistry = new PlanningEvidenceRegistry(evidenceStore);
        PlanNotebook notebook = new PlanNotebook(safeWindows);
        BigDecimal maxBudget = constraintParser.explicitMaxBudget(verifiedContext.effectiveSlots());
        PlanningToolContext planningContext = new PlanningToolContext(
                verifiedContext,
                safeWindows,
                evidenceRegistry,
                notebook,
                maxBudget,
                safeTravel
        );
        ReActAgent agent = agentBuilder.build(planningContext);

        traceService.recordEvent(
                "PLANNING_AGENT_STARTED",
                "AGENT",
                verifiedContext.userGoal(),
                java.util.Map.of("windows", safeWindows)
        );

        try {
            Msg response = agent.call(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(buildPrompt(userInput, verifiedContext, safeWindows))
                            .build(),
                    PlanningDecision.class
            ).block();
            if (response == null) {
                throw new IllegalStateException("PlanningAgent 返回为空");
            }
            PlanningDecision decision = response.getStructuredData(PlanningDecision.class);

            /*
             * AgentScope 的 generate_response 是框架级结构化输出 Tool，不能在 Hook 中通过抛异常阻止，
             * 否则会直接终止整个 ReAct。若模型提前提交最终 PlanningDecision，则在 Worker 边界使用
             * 同一个 validate_plan Tool 对最终 proposal 做确定性补验。这样无论模型是否显式执行最后
             * 一次 Tool 调用，进入业务响应的方案都必须经过同一套 Evidence/Solver/Trace 路径。
             */
            if (!planningContext.notebook().validated()) {
                PlanValidationResult lateValidation = validationTool.validate(decision.plan(), planningContext);
                if (!lateValidation.valid()) {
                    throw new IllegalStateException(
                            "PlanningAgent 最终方案补验失败: " + lateValidation.violations());
                }
            }

            PlanValidationResult finalValidation = validationService.validate(
                    decision.plan(),
                    evidenceRegistry,
                    maxBudget,
                    planningContext.allTravelTimeEvidence()
            );
            if (!finalValidation.valid() || finalValidation.acceptedPlan() == null) {
                throw new IllegalStateException("PlanningAgent 最终方案未通过 Java Solver 复核: " + finalValidation.violations());
            }
            evidenceValidator.validatePlan(finalValidation.acceptedPlan(), evidenceStore);

            PlanningAgentExecutionResult result = new PlanningAgentExecutionResult(
                    decision,
                    finalValidation.acceptedPlan(),
                    finalValidation
            );
            traceService.recordEvent(
                    "PLANNING_AGENT_DECIDED",
                    "AGENT",
                    java.util.Map.of(
                            "periods", evidenceRegistry.periods(),
                            "travelEvidence", planningContext.allTravelTimeEvidence(),
                            "evidence", evidenceStore.snapshot(),
                            "notebook", planningContext.notebook().snapshot()
                    ),
                    result
            );
            return result;
        } catch (RuntimeException error) {
            traceService.recordError(
                    "PLANNING_AGENT_FAILED",
                    "AGENT",
                    java.util.Map.of(
                            "windows", safeWindows,
                            "exposedActivityIds", evidenceRegistry.exposedActivityIds(),
                            "travelEvidence", planningContext.allTravelTimeEvidence(),
                            "evidence", evidenceStore.snapshot(),
                            "notebook", planningContext.notebook().snapshot()
                    ),
                    error
            );
            throw error;
        }
    }

    private String buildPrompt(String userInput,
                               VerifiedRequestContext verifiedContext,
                               List<String> windows) {
        return """
                用户原话：%s
                UserGoal：%s
                规划窗口：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s

                请先调用 discover_plan_candidates 获取真实活动和 OPEN 场次。
                当方案包含不同 venueId 的连续场次时，先调用 get_travel_time 获取对应场次间的真实路线时长证据，
                再调用 validate_plan。若校验失败，根据 violations 定向修改冲突窗口；只有 valid 后才能输出最终 PlanningDecision。
                """.formatted(
                userInput == null ? "" : userInput.trim(),
                verifiedContext.userGoal(),
                windows,
                verifiedContext.effectiveSlots(),
                verifiedContext.hardConstraints()
        );
    }
}

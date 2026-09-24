package com.city.service.worker;

import com.city.agent.builder.PlanningAgentBuilder;
import com.city.enums.DegradationReason;
import com.city.model.TravelTimeEvidence;
import com.city.model.agent.PlanNotebook;
import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.agent.PlanningDecision;
import com.city.model.agent.PlanValidationResult;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.PlanningHorizon;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.PlanningDiscoveryToolResult;
import com.city.service.evidence.DecisionEvidenceValidator;
import com.city.service.evidence.PlanningEvidenceRegistry;
import com.city.service.evidence.RunEvidenceStore;
import com.city.service.harness.AgentExecutionHarness;
import com.city.service.plan.PlanningConstraintParser;
import com.city.service.trace.AgentTraceService;
import com.city.tool.PlanValidationTool;
import com.city.tool.PlanningDiscoveryTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** PlanningAgent 的单 Agent 执行边界。 */
@Component
public final class PlanningAgentWorker {

    private static final int MAX_AGENT_RESPONSE_ATTEMPTS = 8;
    private static final int REPEATED_INVALID_PROPOSAL_THRESHOLD = 3;

    private final PlanningAgentBuilder agentBuilder;
    private final PlanningConstraintParser constraintParser;
    private final PlanValidationTool validationTool;
    private final PlanningDiscoveryTool discoveryTool;
    private final DecisionEvidenceValidator evidenceValidator = new DecisionEvidenceValidator();
    private final AgentTraceService traceService;

    public PlanningAgentWorker(
            PlanningAgentBuilder agentBuilder,
            PlanningConstraintParser constraintParser,
            PlanValidationTool validationTool,
            PlanningDiscoveryTool discoveryTool,
            AgentTraceService traceService
    ) {
        this.agentBuilder = Objects.requireNonNull(agentBuilder, "agentBuilder");
        this.constraintParser = Objects.requireNonNull(constraintParser, "constraintParser");
        this.validationTool = Objects.requireNonNull(validationTool, "validationTool");
        this.discoveryTool = Objects.requireNonNull(discoveryTool, "discoveryTool");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
    }

    public PlanningAgentExecutionResult execute(
            String userInput,
            VerifiedRequestContext verifiedContext,
            PlanningHorizon horizon,
            List<TravelTimeEvidence> travelTimeEvidence
    ) {
        Objects.requireNonNull(verifiedContext, "verifiedContext");
        PlanningHorizon safeHorizon = horizon == null ? PlanningHorizon.empty() : horizon;
        List<TravelTimeEvidence> safeTravel =
                travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);

        RunEvidenceStore evidenceStore = new RunEvidenceStore(verifiedContext.traceId());
        safeTravel.forEach(evidenceStore::recordTravelEvidence);
        PlanningEvidenceRegistry evidenceRegistry = new PlanningEvidenceRegistry(evidenceStore);
        PlanNotebook notebook = new PlanNotebook(safeHorizon);
        BigDecimal maxBudget = constraintParser.explicitMaxBudget(verifiedContext.effectiveSlots());
        PlanningToolContext planningContext = new PlanningToolContext(
                verifiedContext,
                safeHorizon,
                evidenceRegistry,
                notebook,
                maxBudget,
                safeTravel
        );

        // Java 强制首次候选发现；Agent 不能跳过真实 Evidence 获取。
        PlanningDiscoveryToolResult initialCandidates = discoveryTool.discover(planningContext);

        AgentDecisionToolContext decisionToolContext =
                AgentDecisionToolContext.planning(planningContext);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(String.valueOf(verifiedContext.userId()))
                .sessionId(verifiedContext.sessionId())
                .put(AgentDecisionToolContext.class, decisionToolContext)
                .put(PlanningToolContext.class, planningContext)
                .build();

        ReActAgent agent = agentBuilder.build(planningContext);
        Map<String, Integer> invalidProposalSignatures = new HashMap<>();

        traceService.recordEvent(
                "PLANNING_AGENT_STARTED",
                "AGENT",
                verifiedContext.userGoal(),
                Map.of("horizon", safeHorizon)
        );

        try {
            String nextPrompt = buildInitialPrompt(
                    userInput,
                    verifiedContext,
                    safeHorizon,
                    initialCandidates
            );

            for (int responseAttempt = 1; ; responseAttempt++) {
                if (responseAttempt > MAX_AGENT_RESPONSE_ATTEMPTS) {
                    throw new AgentExecutionHarness.AgentHarnessException(
                            "PlanningAgent 已达到单轮响应安全预算上限",
                            DegradationReason.CALL_BUDGET_EXCEEDED
                    );
                }

                PlanningDecision decision;
                try {
                    decision = callDecision(agent, nextPrompt, runtimeContext);
                } catch (RuntimeException responseError) {
                    if (!isRecoverableResponseError(responseError)) throw responseError;

                    traceService.recordEvent(
                            "PLANNING_AGENT_RESPONSE_REPAIR_REQUESTED",
                            "AGENT",
                            Map.of(
                                    "responseAttempt", responseAttempt,
                                    "reason", safeErrorMessage(responseError)
                            ),
                            planningContext.notebook().snapshot()
                    );
                    nextPrompt = buildResponseRepairPrompt(
                            verifiedContext,
                            currentCandidates(evidenceRegistry),
                            safeErrorMessage(responseError)
                    );
                    continue;
                }

                PlanValidationResult validation =
                        validationTool.validate(decision.plan(), planningContext);

                if (validation.valid() && validation.acceptedPlan() != null) {
                    evidenceValidator.validatePlan(validation.acceptedPlan(), evidenceStore);
                    PlanningAgentExecutionResult result = new PlanningAgentExecutionResult(
                            decision,
                            validation.acceptedPlan(),
                            validation
                    );
                    traceService.recordEvent(
                            "PLANNING_AGENT_DECIDED",
                            "AGENT",
                            Map.of(
                                    "responseAttempts", responseAttempt,
                                    "searchedRanges", evidenceRegistry.searchedRanges(),
                                    "travelEvidence", planningContext.allTravelTimeEvidence(),
                                    "evidence", evidenceStore.snapshot(),
                                    "notebook", planningContext.notebook().snapshot()
                            ),
                            result
                    );
                    return result;
                }

                String signature = proposalSignature(decision.plan());
                int repeated = invalidProposalSignatures.merge(signature, 1, Integer::sum);
                if (repeated >= REPEATED_INVALID_PROPOSAL_THRESHOLD) {
                    throw new AgentExecutionHarness.AgentHarnessException(
                            "PlanningAgent 连续提交重复的无效方案，已停止修复循环",
                            DegradationReason.LOOP_DETECTED
                    );
                }

                PlanningDiscoveryToolResult candidates = currentCandidates(evidenceRegistry);
                traceService.recordEvent(
                        "PLANNING_AGENT_REPAIR_REQUESTED",
                        "AGENT",
                        Map.of(
                                "responseAttempt", responseAttempt,
                                "violations", validation.violations(),
                                "notebook", planningContext.notebook().snapshot()
                        ),
                        decision
                );

                nextPrompt = buildRepairPrompt(
                        verifiedContext,
                        candidates,
                        planningContext.notebook().snapshot(),
                        repeated
                );
            }
        } catch (RuntimeException error) {
            traceService.recordError(
                    "PLANNING_AGENT_FAILED",
                    "AGENT",
                    Map.of(
                            "horizon", safeHorizon,
                            "searchedRanges", evidenceRegistry.searchedRanges(),
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

    private PlanningDiscoveryToolResult currentCandidates(PlanningEvidenceRegistry evidenceRegistry) {
        return PlanningDiscoveryToolResult.from(
                evidenceRegistry.searchedRanges(),
                evidenceRegistry.activities(),
                evidenceRegistry.sessionsByActivityId()
        );
    }

    private PlanningDecision callDecision(
            ReActAgent agent,
            String prompt,
            RuntimeContext runtimeContext
    ) {
        Msg response = agent.call(
                List.of(Msg.builder().role(MsgRole.USER).textContent(prompt).build()),
                PlanningDecision.class,
                runtimeContext
        ).block();
        return structuredDecision(response);
    }

    void ensureToolValidated(PlanningDecision decision, PlanningToolContext planningContext) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(planningContext, "planningContext");
        if (planningContext.notebook().validated()) return;

        PlanValidationResult result = validationTool.validate(decision.plan(), planningContext);
        if (!result.valid()) {
            throw new IllegalStateException(
                    "PlanningAgent 最终方案补验失败: " + result.violations());
        }
    }

    private PlanningDecision structuredDecision(Msg response) {
        if (response == null) {
            throw new InvalidPlanningResponseException("PlanningAgent 返回为空");
        }
        PlanningDecision decision = response.getStructuredData(PlanningDecision.class);
        if (decision == null) {
            throw new InvalidPlanningResponseException(
                    "PlanningAgent 未返回结构化 PlanningDecision");
        }
        if (decision.plan() == null
                || decision.plan().items() == null
                || decision.plan().items().isEmpty()) {
            throw new InvalidPlanningResponseException(
                    "PlanningDecision.plan 不能为空");
        }
        return decision;
    }

    private String buildInitialPrompt(
            String userInput,
            VerifiedRequestContext context,
            PlanningHorizon horizon,
            PlanningDiscoveryToolResult initialCandidates
    ) {
        return """
                用户原话：%s
                UserGoal：%s
                PlanningHorizon：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s
                首轮已验证候选与真实 sessions：%s

                服务器已经按完整日期/时间限制执行首次候选发现。
                你负责用真实 session 时间组合行程，不需要把行程填满整个 horizon。
                如果你选定的某个时间缺口没有合适候选，可调用 search_plan_candidates(startAt,endAt,retrievalIntent) 局部补充。
                需要判断两个真实场次之间的交通时可调用 get_travel_time。
                对有 sessions 的 activity 必须明确选择真实 OPEN sessionId。
                对没有具体 sessions 的 activity，必须给出 horizon 内的 plannedStartAt/plannedEndAt。
                信息足够后直接提交完整 PlanningDecision。
                """.formatted(
                userInput == null ? "" : userInput.trim(),
                context.userGoal(),
                horizon,
                context.effectiveSlots(),
                context.hardConstraints(),
                initialCandidates
        );
    }

    private boolean isRecoverableResponseError(RuntimeException error) {
        if (error instanceof InvalidPlanningResponseException) return true;
        String message = safeErrorMessage(error).toLowerCase(java.util.Locale.ROOT);
        return message.contains("structured")
                || message.contains("deserialize")
                || message.contains("json")
                || message.contains("planningdecision");
    }

    private String safeErrorMessage(Throwable error) {
        if (error == null) return "unknown response error";
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private String buildResponseRepairPrompt(
            VerifiedRequestContext context,
            PlanningDiscoveryToolResult currentCandidates,
            String reason
    ) {
        return """
                上一个响应没有形成可校验的 PlanningDecision，需要重新提交。

                UserGoal：%s
                当前完整已验证候选：%s
                响应问题：%s

                你仍然可以调用 search_plan_candidates、get_travel_time 等已注册 Tool。
                最终 plan 不能为空；activityId/sessionId 必须引用当前 Run 真实 Evidence。
                无固定 session 的 activity 必须提供 plannedStartAt/plannedEndAt。
                """.formatted(
                context.userGoal(),
                currentCandidates,
                reason == null ? "" : reason
        );
    }

    private String buildRepairPrompt(
            VerifiedRequestContext context,
            PlanningDiscoveryToolResult currentCandidates,
            PlanNotebook.Snapshot notebook,
            int repeatedSameProposal
    ) {
        String stagnationHint = repeatedSameProposal > 1
                ? "同一无效 proposal 已重复 " + repeatedSameProposal
                        + " 次；本轮必须采取不同动作，不能只修改解释文案。"
                : "";
        return """
                上一个 PlanningDecision 没有通过服务器校验，需要继续修复。

                UserGoal：%s
                PlanningHorizon：%s
                当前完整已验证候选：%s
                上一方案：%s
                violations：%s
                停滞提示：%s

                请逐条处理 violations 的 message 和 repairHint。
                候选时间覆盖不足时，调用 search_plan_candidates(startAt,endAt,retrievalIntent) 搜索缺口。
                如果是 MISSING_TRAVEL_EVIDENCE，repairHint 已包含完整 get_travel_time(...) 参数，先调用路线 Tool。
                Java 不会替你枚举或选择 activity/session。
                修复后重新提交完整 PlanningDecision，不要原样重复无效方案。
                """.formatted(
                context.userGoal(),
                notebook.horizon(),
                currentCandidates,
                notebook.latestProposal(),
                notebook.latestViolations(),
                stagnationHint
        );
    }

    private String proposalSignature(PlanProposal proposal) {
        if (proposal == null || proposal.items() == null) return "<null>";
        return proposal.items().stream()
                .filter(Objects::nonNull)
                .map(item -> item.activityId()
                        + ":" + item.sessionId()
                        + ":" + item.plannedStartAt()
                        + ":" + item.plannedEndAt())
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElse("<empty>");
    }

    private static final class InvalidPlanningResponseException extends RuntimeException {
        private InvalidPlanningResponseException(String message) {
            super(message);
        }
    }
}

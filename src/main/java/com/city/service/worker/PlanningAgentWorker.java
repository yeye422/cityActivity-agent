package com.city.service.worker;

import com.city.agent.builder.PlanningAgentBuilder;
import com.city.model.ActivitySessionResponse;
import com.city.model.TravelTimeEvidence;
import com.city.model.agent.PlanNotebook;
import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.agent.PlanningDecision;
import com.city.model.agent.PlanValidationResult;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.PlanningDiscoveryToolResult;
import com.city.service.evidence.DecisionEvidenceValidator;
import com.city.service.evidence.PlanningEvidenceRegistry;
import com.city.service.evidence.RunEvidenceStore;
import com.city.service.plan.PlanningConstraintParser;
import com.city.service.trace.AgentTraceService;
import com.city.tool.PlanValidationTool;
import com.city.tool.PlanningDiscoveryTool;
import com.city.tool.TravelTimeTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * PlanningAgent 的执行边界。不写 SessionState；Agent 负责活动与具体场次选择，
 * Java 强制执行候选事实、路线事实、validate_plan 与 Evidence Gate。
 */
@Component
public final class PlanningAgentWorker {

    private final PlanningAgentBuilder agentBuilder;
    private final PlanningConstraintParser constraintParser;
    private final PlanValidationTool validationTool;
    private final PlanningDiscoveryTool discoveryTool;
    private final TravelTimeTool travelTimeTool;
    private final DecisionEvidenceValidator evidenceValidator = new DecisionEvidenceValidator();
    private final AgentTraceService traceService;

    public PlanningAgentWorker(
            PlanningAgentBuilder agentBuilder,
            PlanningConstraintParser constraintParser,
            PlanValidationTool validationTool,
            PlanningDiscoveryTool discoveryTool,
            TravelTimeTool travelTimeTool,
            AgentTraceService traceService
    ) {
        this.agentBuilder = Objects.requireNonNull(agentBuilder, "agentBuilder");
        this.constraintParser = Objects.requireNonNull(constraintParser, "constraintParser");
        this.validationTool = Objects.requireNonNull(validationTool, "validationTool");
        this.discoveryTool = Objects.requireNonNull(discoveryTool, "discoveryTool");
        this.travelTimeTool = Objects.requireNonNull(travelTimeTool, "travelTimeTool");
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

        // 候选发现是事实获取边界，必须在 Agent 决策前发生。
        PlanningDiscoveryToolResult preloadedCandidates = discoveryTool.discover(planningContext);

        AgentDecisionToolContext decisionToolContext = AgentDecisionToolContext.planning(planningContext);
        RuntimeContext explorerRuntime = RuntimeContext.builder()
                .userId(String.valueOf(verifiedContext.userId()))
                .sessionId(verifiedContext.sessionId())
                .put(AgentDecisionToolContext.class, decisionToolContext)
                .build();
        RuntimeContext finalizerRuntime = RuntimeContext.builder()
                .userId(String.valueOf(verifiedContext.userId()))
                .sessionId(verifiedContext.sessionId())
                .build();

        traceService.recordEvent(
                "PLANNING_AGENT_STARTED",
                "AGENT",
                verifiedContext.userGoal(),
                java.util.Map.of("windows", safeWindows)
        );

        try {
            String explorationNotes = explore(
                    agentBuilder.buildExplorer(planningContext),
                    buildExplorationPrompt(userInput, verifiedContext, safeWindows, preloadedCandidates),
                    explorerRuntime,
                    verifiedContext
            );
            PlanningDiscoveryToolResult enrichedCandidates =
                    PlanningDiscoveryToolResult.from(evidenceRegistry.windows());

            ReActAgent finalizer = agentBuilder.buildFinalizer();
            PlanningDecision decision = callDecision(
                    finalizer,
                    buildPrompt(
                            userInput,
                            verifiedContext,
                            safeWindows,
                            enrichedCandidates,
                            explorationNotes
                    ),
                    finalizerRuntime
            );

            PlanValidationResult finalValidation;
            try {
                finalValidation = validateWithServerFacts(decision, planningContext);
            } catch (IllegalStateException firstValidationError) {
                if (planningContext.notebook().status() != PlanNotebook.Status.REPAIR_REQUIRED) {
                    throw firstValidationError;
                }
                traceService.recordEvent(
                        "PLANNING_AGENT_REPAIR_REQUESTED",
                        "AGENT",
                        planningContext.notebook().snapshot(),
                        java.util.Map.of("reason", firstValidationError.getMessage())
                );

                decision = callDecision(
                        finalizer,
                        buildRepairPrompt(planningContext, enrichedCandidates, explorationNotes),
                        finalizerRuntime
                );
                finalValidation = validateWithServerFacts(decision, planningContext);
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

    private PlanningDecision callDecision(ReActAgent agent,
                                          String prompt,
                                          RuntimeContext runtimeContext) {
        Msg response = agent.call(
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent(prompt)
                                .build()
                ),
                PlanningDecision.class,
                runtimeContext
        ).block();
        return structuredDecision(response);
    }

    /**
     * 根据 Agent 已选场次确定性补齐相邻跨场地路线证据。
     * Agent 不决定是否查询路线；Java 根据 proposal 的真实 venueId 自动执行。
     */
    void ensureTravelEvidence(PlanProposal proposal, PlanningToolContext planningContext) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(planningContext, "planningContext");

        List<SelectedSession> selected = new ArrayList<>();
        for (PlanProposal.Item item : proposal.items()) {
            if (item == null || item.sessionId() == null) continue;
            ActivitySessionResponse session = planningContext.evidenceRegistry().session(
                    item.period(), item.activityId(), item.sessionId());
            if (session == null || session.startAt() == null) continue;
            selected.add(new SelectedSession(item.period(), item.activityId(), session));
        }
        selected.sort(Comparator.comparing(item -> item.session().startAt()));
        ensureTravelEvidence(selected, planningContext);
    }

    private PlanValidationResult validateWithServerFacts(
            PlanningDecision decision,
            PlanningToolContext planningContext
    ) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(planningContext, "planningContext");

        // Agent 已经明确选择真实 session；Java 只根据这些精确引用补齐路线事实。
        ensureTravelEvidence(decision.plan(), planningContext);

        PlanValidationResult result = validationTool.validate(decision.plan(), planningContext);
        if (!result.valid() || result.acceptedPlan() == null) {
            throw new IllegalStateException(
                    "PlanningAgent 精确方案校验失败: " + result.violations());
        }
        return result;
    }

    /** package-private：回归测试 Java 强制 validate_plan 边界。 */
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

    private boolean hasTravelEvidence(Long fromVenueId,
                                      Long toVenueId,
                                      List<TravelTimeEvidence> evidence) {
        return evidence.stream().anyMatch(item -> item != null
                && fromVenueId.equals(item.fromVenueId())
                && toVenueId.equals(item.toVenueId()));
    }

    private PlanningDecision structuredDecision(Msg response) {
        if (response == null) {
            throw new IllegalStateException("PlanningAgent 返回为空");
        }
        PlanningDecision decision = response.getStructuredData(PlanningDecision.class);
        if (decision == null) {
            throw new IllegalStateException("PlanningAgent 未返回结构化 PlanningDecision");
        }
        return decision;
    }

    private String buildExplorationPrompt(String userInput,
                                          VerifiedRequestContext verifiedContext,
                                          List<String> windows,
                                          PlanningDiscoveryToolResult preloadedCandidates) {
        return """
                用户原话：%s
                UserGoal：%s
                规划窗口：%s
                当前已生效槽位：%s
                首轮已验证候选：%s

                判断这些候选是否足以支持 UserGoal。
                只有确有信息缺口时才调用 Tool；完成后给 Finalizer 一段简短探索摘要。
                """.formatted(
                userInput == null ? "" : userInput.trim(),
                verifiedContext.userGoal(),
                windows,
                verifiedContext.effectiveSlots(),
                preloadedCandidates
        );
    }

    private String explore(ReActAgent explorer,
                           String prompt,
                           RuntimeContext runtimeContext,
                           VerifiedRequestContext verifiedContext) {
        try {
            Msg response = explorer.call(
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .textContent(prompt)
                                    .build()
                    ),
                    runtimeContext
            ).block();
            String notes = response == null ? "" : response.getTextContent();
            return notes == null ? "" : notes.trim();
        } catch (RuntimeException error) {
            traceService.recordError(
                    "PLANNING_EXPLORATION_DEGRADED",
                    "AGENT",
                    java.util.Map.of("userGoal", verifiedContext.userGoal()),
                    error
            );
            return "";
        }
    }

    private String buildPrompt(String userInput,
                               VerifiedRequestContext verifiedContext,
                               List<String> windows,
                               PlanningDiscoveryToolResult candidates,
                               String explorationNotes) {
        return """
                用户原话：%s
                UserGoal：%s
                规划窗口：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s
                Explorer 后的完整已验证候选快照：%s
                Explorer 摘要：%s

                你只负责在上述真实候选中做软目标组合。
                Explorer 摘要只提供软决策参考，事实以候选快照为准。
                每个 PlanProposal.Item 只能引用候选快照中已经存在的 period/activityId/sessionId。
                某 activity 只要暴露了具体 sessions，就必须由你明确选择其中一个真实 OPEN sessionId。
                Java 不会替你枚举或选择 session，只会补齐必要路线证据并强制执行 validate_plan。
                请直接提交完整 PlanningDecision，不要输出空 plan。
                """.formatted(
                userInput == null ? "" : userInput.trim(),
                verifiedContext.userGoal(),
                windows,
                verifiedContext.effectiveSlots(),
                verifiedContext.hardConstraints(),
                candidates,
                explorationNotes == null ? "" : explorationNotes
        );
    }

    private String buildRepairPrompt(PlanningToolContext planningContext,
                                     PlanningDiscoveryToolResult candidates,
                                     String explorationNotes) {
        return """
                上一个 proposal 没有通过服务器 validate_plan。
                当前 Notebook：%s
                当前完整候选快照：%s
                Explorer 摘要：%s

                请严格根据 latestViolations/repairHint 修改冲突窗口，并重新提交完整 PlanningDecision。
                只能引用当前已验证候选中的 period/activityId/sessionId。
                如果 violation 指向 session，必须由你改选另一个真实 sessionId；Java 不替你选择。
                路线证据和 validate_plan 均由服务器自动执行。
                不要返回空 plan，也不要原样重复上一个已拒绝 proposal。
                """.formatted(
                planningContext.notebook().snapshot(),
                candidates,
                explorationNotes == null ? "" : explorationNotes
        );
    }

    private record SelectedSession(
            String period,
            Long activityId,
            ActivitySessionResponse session
    ) {}
}

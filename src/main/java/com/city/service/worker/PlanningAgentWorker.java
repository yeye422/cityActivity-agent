package com.city.service.worker;

import com.city.agent.builder.PlanningAgentBuilder;
import com.city.enums.DegradationReason;
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
import com.city.service.harness.AgentExecutionHarness;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PlanningAgent 的单 Agent 执行边界。
 *
 * <p>同一个 Agent 负责按需调用探索 Tool、选择 activity/session、提交 PlanningDecision，
 * 并根据 Java validate_plan 返回的 violations 持续修复。路线时长作为受控 Tool 由 Agent 按需查询；
 * Java 负责硬约束校验和 Evidence Gate，不替 Agent 枚举或选择活动/场次。</p>
 */
@Component
public final class PlanningAgentWorker {

    /** 只用于防失控，不代表业务固定修复轮数。 */
    private static final int MAX_AGENT_RESPONSE_ATTEMPTS = 8;
    private static final int REPEATED_INVALID_PROPOSAL_THRESHOLD = 3;

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
        List<TravelTimeEvidence> safeTravel =
                travelTimeEvidence == null ? List.of() : List.copyOf(travelTimeEvidence);

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

        // 首次候选发现是事实边界，PlanningAgent 不能跳过。
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
                Map.of("windows", safeWindows)
        );

        try {
            String nextPrompt = buildInitialPrompt(
                    userInput,
                    verifiedContext,
                    safeWindows,
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
                    if (!isRecoverableResponseError(responseError)) {
                        throw responseError;
                    }
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
                            PlanningDiscoveryToolResult.from(evidenceRegistry.windows()),
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
                                    "periods", evidenceRegistry.periods(),
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

                PlanningDiscoveryToolResult currentCandidates =
                        PlanningDiscoveryToolResult.from(evidenceRegistry.windows());
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
                        currentCandidates,
                        planningContext.notebook().snapshot()
                );
            }
        } catch (RuntimeException error) {
            traceService.recordError(
                    "PLANNING_AGENT_FAILED",
                    "AGENT",
                    Map.of(
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

    private PlanningDecision callDecision(
            ReActAgent agent,
            String prompt,
            RuntimeContext runtimeContext
    ) {
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
     * Java 不选择 session，只查询 Agent 已选择 session 之间的真实路线。
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

        for (int i = 1; i < selected.size(); i++) {
            SelectedSession from = selected.get(i - 1);
            SelectedSession to = selected.get(i);
            Long fromVenue = from.session().venueId();
            Long toVenue = to.session().venueId();
            if (fromVenue == null || toVenue == null || fromVenue.equals(toVenue)) continue;
            if (hasTravelEvidence(fromVenue, toVenue, planningContext.allTravelTimeEvidence())) continue;

            travelTimeTool.getTravelTime(
                    from.period(),
                    from.activityId(),
                    from.session().sessionId(),
                    to.period(),
                    to.activityId(),
                    to.session().sessionId(),
                    planningContext
            );
        }
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

    private boolean hasTravelEvidence(
            Long fromVenueId,
            Long toVenueId,
            List<TravelTimeEvidence> evidence
    ) {
        return evidence.stream().anyMatch(item -> item != null
                && fromVenueId.equals(item.fromVenueId())
                && toVenueId.equals(item.toVenueId()));
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
            List<String> windows,
            PlanningDiscoveryToolResult initialCandidates
    ) {
        return """
                用户原话：%s
                UserGoal：%s
                规划窗口：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s
                首轮已验证候选：%s

                这是本轮规划的首次提案。
                你可以按需调用已注册 Tool 补充候选、活动详情、长期偏好、近期推荐历史或查询已选场次之间的路线时间。
                信息足够后直接提交完整 PlanningDecision。
                每个 item 必须引用当前 Run 已暴露的真实 period/activityId/sessionId。
                如果 activity 暴露了具体 sessions，必须由你明确选择一个真实 OPEN sessionId。
                """.formatted(
                userInput == null ? "" : userInput.trim(),
                context.userGoal(),
                windows,
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
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
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

                你仍然可以按需调用已注册 Tool，但最终必须提交完整 PlanningDecision。
                plan 不能为空；每个 item 必须使用真实 period/activityId，
                对暴露了具体 sessions 的 activity 必须明确选择真实 OPEN sessionId。
                """.formatted(
                context.userGoal(),
                currentCandidates,
                reason == null ? "" : reason
        );
    }

    private String buildRepairPrompt(
            VerifiedRequestContext context,
            PlanningDiscoveryToolResult currentCandidates,
            PlanNotebook.Snapshot notebook
    ) {
        return """
                上一个 PlanningDecision 没有通过服务器校验，需要继续修复。

                UserGoal：%s
                当前完整已验证候选：%s
                校验状态：%s
                上一方案：%s
                violations：%s

                请逐条处理 violations 中的 message 和 repairHint。
                你仍然可以按需调用已注册 Tool 获取补充信息、扩展候选或查询路线时间。
                如果 violation 是 MISSING_TRAVEL_EVIDENCE，应调用 get_travel_time 查询对应两个真实 session 的路线时间后再提交。
                必须由你重新选择 activity/session；Java 不会替你枚举或选择。
                修复后重新提交完整 PlanningDecision。
                不要原样重复上一份无效方案。
                """.formatted(
                context.userGoal(),
                currentCandidates,
                notebook.status(),
                notebook.latestProposal(),
                notebook.latestViolations()
        );
    }

    private String proposalSignature(PlanProposal proposal) {
        if (proposal == null || proposal.items() == null) return "<null>";
        return proposal.items().stream()
                .filter(Objects::nonNull)
                .map(item -> String.valueOf(item.period())
                        + ":" + item.activityId()
                        + ":" + item.sessionId())
                .sorted()
                .reduce((left, right) -> left + "|" + right)
                .orElse("<empty>");
    }

    private record SelectedSession(
            String period,
            Long activityId,
            ActivitySessionResponse session
    ) {}
    private static final class InvalidPlanningResponseException extends RuntimeException {
        private InvalidPlanningResponseException(String message) {
            super(message);
        }
    }

}

package com.city.service.worker;

import com.city.agent.builder.RecommendationAgentBuilder;
import com.city.enums.DegradationReason;
import com.city.model.ActivityItem;
import com.city.model.agent.RecommendationDecision;
import com.city.model.agent.RecommendationExecutionResult;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.RetrievalToolResult;
import com.city.service.evidence.CandidateEvidenceRegistry;
import com.city.service.evidence.DecisionEvidenceValidator;
import com.city.service.evidence.RunEvidenceStore;
import com.city.service.harness.AgentExecutionHarness;
import com.city.service.recommend.RecommendationDecisionValidator;
import com.city.service.trace.AgentTraceService;
import com.city.tool.RetrievalTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RecommendationAgent 的单 Agent 执行边界。
 *
 * <p>Java 强制首次检索并维护硬约束/Evidence；同一个 Agent 决定是否补充检索、查看详情、
 * 使用偏好/历史、选择候选，并根据 Java 校验失败原因持续修复。</p>
 */
@Component
public final class RecommendationWorker {
    private static final int MAX_RETRIEVAL_CALLS = 2;
    /** 只用于防失控，不代表业务固定修复轮数。 */
    private static final int MAX_AGENT_RESPONSE_ATTEMPTS = 8;
    private static final int REPEATED_INVALID_DECISION_THRESHOLD = 3;

    private final RecommendationAgentBuilder agentBuilder;
    private final RecommendationDecisionValidator decisionValidator;
    private final RetrievalTool retrievalTool;
    private final DecisionEvidenceValidator evidenceValidator = new DecisionEvidenceValidator();
    private final AgentTraceService traceService;

    public RecommendationWorker(
            RecommendationAgentBuilder agentBuilder,
            RecommendationDecisionValidator decisionValidator,
            RetrievalTool retrievalTool,
            AgentTraceService traceService
    ) {
        this.agentBuilder = Objects.requireNonNull(agentBuilder, "agentBuilder");
        this.decisionValidator = Objects.requireNonNull(decisionValidator, "decisionValidator");
        this.retrievalTool = Objects.requireNonNull(retrievalTool, "retrievalTool");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
    }

    public RecommendationExecutionResult execute(
            String userInput,
            VerifiedRequestContext verifiedContext
    ) {
        Objects.requireNonNull(verifiedContext, "verifiedContext");

        RunEvidenceStore evidenceStore = new RunEvidenceStore(verifiedContext.traceId());
        CandidateEvidenceRegistry evidenceRegistry = new CandidateEvidenceRegistry(
                MAX_RETRIEVAL_CALLS,
                evidenceStore
        );

        // 首次检索是事实边界，必须发生；是否补充第二次检索由 Agent 自己决定。
        RetrievalToolResult initialCandidates = retrievalTool.searchActivities(
                initialRetrievalIntent(userInput, verifiedContext),
                verifiedContext,
                evidenceRegistry
        );

        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(String.valueOf(verifiedContext.userId()))
                .sessionId(verifiedContext.sessionId())
                .put(VerifiedRequestContext.class, verifiedContext)
                .put(CandidateEvidenceRegistry.class, evidenceRegistry)
                .put(
                        AgentDecisionToolContext.class,
                        AgentDecisionToolContext.recommendation(
                                verifiedContext,
                                evidenceRegistry
                        )
                )
                .build();

        ReActAgent agent = agentBuilder.build(verifiedContext);
        Map<String, Integer> invalidDecisionSignatures = new HashMap<>();

        traceService.recordEvent(
                "RECOMMENDATION_AGENT_STARTED",
                "AGENT",
                verifiedContext.userGoal(),
                Map.of("initialCandidates", initialCandidates)
        );

        try {
            String nextPrompt = buildInitialPrompt(
                    userInput,
                    verifiedContext,
                    initialCandidates
            );

            for (int responseAttempt = 1; ; responseAttempt++) {
                if (responseAttempt > MAX_AGENT_RESPONSE_ATTEMPTS) {
                    throw new AgentExecutionHarness.AgentHarnessException(
                            "RecommendationAgent 已达到单轮响应安全预算上限",
                            DegradationReason.CALL_BUDGET_EXCEEDED
                    );
                }

                RecommendationDecision decision;
                try {
                    decision = callDecision(agent, nextPrompt, runtimeContext);
                } catch (RuntimeException responseError) {
                    if (!isRecoverableResponseError(responseError)) {
                        throw responseError;
                    }
                    traceService.recordEvent(
                            "RECOMMENDATION_AGENT_RESPONSE_REPAIR_REQUESTED",
                            "AGENT",
                            Map.of(
                                    "responseAttempt", responseAttempt,
                                    "reason", safeErrorMessage(responseError)
                            ),
                            currentCandidates(evidenceStore, evidenceRegistry)
                    );
                    nextPrompt = buildResponseRepairPrompt(
                            verifiedContext,
                            currentCandidates(evidenceStore, evidenceRegistry),
                            evidenceRegistry,
                            safeErrorMessage(responseError)
                    );
                    continue;
                }

                try {
                    RecommendationDecision validated =
                            decisionValidator.validate(decision, evidenceRegistry);
                    List<ActivityItem> selected =
                            evidenceValidator.validateRecommendation(
                                    validated.selectedActivityIds(),
                                    evidenceStore
                            );

                    RecommendationExecutionResult result =
                            new RecommendationExecutionResult(
                                    validated,
                                    selected,
                                    evidenceRegistry.retrievalCalls(),
                                    evidenceRegistry.rounds()
                            );
                    traceService.recordEvent(
                            "RECOMMENDATION_DECIDED",
                            "AGENT",
                            Map.of(
                                    "responseAttempts", responseAttempt,
                                    "retrievalCalls", evidenceRegistry.retrievalCalls(),
                                    "rounds", evidenceRegistry.rounds(),
                                    "evidence", evidenceStore.snapshot()
                            ),
                            result
                    );
                    return result;
                } catch (RuntimeException validationError) {
                    String signature = decisionSignature(decision);
                    int repeated = invalidDecisionSignatures.merge(
                            signature,
                            1,
                            Integer::sum
                    );
                    if (repeated >= REPEATED_INVALID_DECISION_THRESHOLD) {
                        throw new AgentExecutionHarness.AgentHarnessException(
                                "RecommendationAgent 连续提交重复的无效决策，已停止修复循环",
                                DegradationReason.LOOP_DETECTED
                        );
                    }

                    RetrievalToolResult candidates =
                            currentCandidates(evidenceStore, evidenceRegistry);
                    traceService.recordEvent(
                            "RECOMMENDATION_AGENT_REPAIR_REQUESTED",
                            "AGENT",
                            Map.of(
                                    "responseAttempt", responseAttempt,
                                    "reason", safeErrorMessage(validationError),
                                    "retrievalCalls", evidenceRegistry.retrievalCalls(),
                                    "candidateCount", candidates.candidates().size()
                            ),
                            decision
                    );
                    nextPrompt = buildRepairPrompt(
                            verifiedContext,
                            candidates,
                            evidenceRegistry,
                            decision,
                            safeErrorMessage(validationError)
                    );
                }
            }
        } catch (RuntimeException error) {
            traceService.recordError(
                    "RECOMMENDATION_AGENT_FAILED",
                    "AGENT",
                    Map.of(
                            "retrievalCalls", evidenceRegistry.retrievalCalls(),
                            "rounds", evidenceRegistry.rounds(),
                            "evidence", evidenceStore.snapshot()
                    ),
                    error
            );
            throw error;
        }
    }

    private RecommendationDecision callDecision(
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
                RecommendationDecision.class,
                runtimeContext
        ).block();
        return structuredDecision(response);
    }

    private RecommendationDecision structuredDecision(Msg response) {
        if (response == null) {
            throw new InvalidRecommendationResponseException(
                    "RecommendationAgent 返回为空"
            );
        }
        RecommendationDecision decision =
                response.getStructuredData(RecommendationDecision.class);
        if (decision == null) {
            throw new InvalidRecommendationResponseException(
                    "RecommendationAgent 未返回结构化 RecommendationDecision"
            );
        }
        return decision;
    }

    private RetrievalToolResult currentCandidates(
            RunEvidenceStore evidenceStore,
            CandidateEvidenceRegistry evidenceRegistry
    ) {
        return RetrievalToolResult.from(
                "all-exposed",
                evidenceStore.resolveActivities(
                        evidenceRegistry.exposedActivityIds().stream().toList()
                )
        );
    }

    private String buildInitialPrompt(
            String userInput,
            VerifiedRequestContext context,
            RetrievalToolResult initialCandidates
    ) {
        return """
                用户原话：%s
                UserGoal：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s
                首轮已验证候选：%s

                服务器已经完成第一次真实检索。
                先判断当前候选是否足以支持 UserGoal；只有确有信息缺口时才调用 Tool。
                search_activities 最多还能补充一次检索。
                信息足够后直接提交完整 RecommendationDecision。
                selectedActivityIds 和 assessments.activityId 只能引用当前 Run 已暴露候选。
                """.formatted(
                userInput == null ? "" : userInput.trim(),
                context.userGoal(),
                context.effectiveSlots(),
                context.hardConstraints(),
                initialCandidates
        );
    }

    private String buildResponseRepairPrompt(
            VerifiedRequestContext context,
            RetrievalToolResult candidates,
            CandidateEvidenceRegistry evidenceRegistry,
            String reason
    ) {
        return """
                上一个响应没有形成可校验的 RecommendationDecision，需要重新提交。

                UserGoal：%s
                当前完整已验证候选：%s
                已使用检索次数：%d / %d
                响应问题：%s

                你仍然可以按需调用已注册 Tool。
                最终必须提交完整 RecommendationDecision；
                selectedActivityIds 不能为空，所有 activityId 必须来自当前候选。
                如果已经达到检索上限，必须在现有候选中 best-effort 完成推荐。
                """.formatted(
                context.userGoal(),
                candidates,
                evidenceRegistry.retrievalCalls(),
                MAX_RETRIEVAL_CALLS,
                reason == null ? "" : reason
        );
    }

    private String buildRepairPrompt(
            VerifiedRequestContext context,
            RetrievalToolResult candidates,
            CandidateEvidenceRegistry evidenceRegistry,
            RecommendationDecision previousDecision,
            String reason
    ) {
        return """
                上一个 RecommendationDecision 没有通过服务器校验，需要继续修复。

                UserGoal：%s
                当前完整已验证候选：%s
                已使用检索次数：%d / %d
                上一决策：%s
                校验失败原因：%s

                请直接处理失败原因。
                如果候选确实不足且仍有检索额度，可以调用 search_activities 补充一次；
                如果检索额度已经耗尽，必须在当前真实候选中 best-effort 选择 1~3 个。
                selectedActivityIds 和 assessments.activityId 必须全部来自当前候选。
                不要原样重复上一份无效决策。
                """.formatted(
                context.userGoal(),
                candidates,
                evidenceRegistry.retrievalCalls(),
                MAX_RETRIEVAL_CALLS,
                previousDecision,
                reason == null ? "" : reason
        );
    }

    private boolean isRecoverableResponseError(RuntimeException error) {
        if (error instanceof InvalidRecommendationResponseException) return true;
        String message = safeErrorMessage(error)
                .toLowerCase(java.util.Locale.ROOT);
        return message.contains("structured")
                || message.contains("deserialize")
                || message.contains("json")
                || message.contains("recommendationdecision");
    }

    private String safeErrorMessage(Throwable error) {
        if (error == null) return "unknown response error";
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.length() <= 500
                ? message
                : message.substring(0, 500);
    }

    private String decisionSignature(RecommendationDecision decision) {
        if (decision == null) return "<null>";
        List<Long> selected = decision.selectedActivityIds() == null
                ? List.of()
                : decision.selectedActivityIds().stream()
                        .filter(Objects::nonNull)
                        .sorted()
                        .toList();
        return selected + "|pool=" + decision.candidatePoolSufficient();
    }

    private String initialRetrievalIntent(
            String userInput,
            VerifiedRequestContext context
    ) {
        String safeInput = userInput == null ? "" : userInput.trim();
        String semantic = context.userGoal().semanticQuery(context.effectiveSlots());
        return semantic.isBlank() ? safeInput : semantic;
    }

    private static final class InvalidRecommendationResponseException
            extends RuntimeException {
        private InvalidRecommendationResponseException(String message) {
            super(message);
        }
    }
}

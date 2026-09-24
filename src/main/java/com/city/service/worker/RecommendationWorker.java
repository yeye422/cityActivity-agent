package com.city.service.worker;

import com.city.agent.builder.RecommendationAgentBuilder;
import com.city.model.ActivityItem;
import com.city.model.agent.RecommendationDecision;
import com.city.model.agent.RecommendationExecutionResult;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.evidence.CandidateEvidenceRegistry;
import com.city.service.evidence.DecisionEvidenceValidator;
import com.city.service.evidence.RunEvidenceStore;
import com.city.service.recommend.RecommendationDecisionValidator;
import com.city.service.trace.AgentTraceService;
import com.city.tool.RetrievalTool;
import com.city.model.tool.RetrievalToolResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;

/**
 * RecommendationAgent 的执行边界。
 *
 * <p>不写 SessionState。Agent 可以通过 search_activities 自主检索，并在候选池不足时
 * 调整软检索意图再检索一次；最终实体必须通过当前 Run 的 Evidence 门禁。</p>
 */
@Component
public final class RecommendationWorker {
    private static final int MAX_RETRIEVAL_CALLS = 2;

    private final RecommendationAgentBuilder agentBuilder;
    private final RecommendationDecisionValidator decisionValidator;
    private final RetrievalTool retrievalTool;
    private final DecisionEvidenceValidator evidenceValidator = new DecisionEvidenceValidator();
    private final AgentTraceService traceService;

    public RecommendationWorker(RecommendationAgentBuilder agentBuilder,
                                RecommendationDecisionValidator decisionValidator,
                                RetrievalTool retrievalTool,
                                AgentTraceService traceService) {
        this.agentBuilder = Objects.requireNonNull(agentBuilder, "agentBuilder");
        this.decisionValidator = Objects.requireNonNull(decisionValidator, "decisionValidator");
        this.retrievalTool = Objects.requireNonNull(retrievalTool, "retrievalTool");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
    }

    public RecommendationExecutionResult execute(String userInput,
                                                 VerifiedRequestContext verifiedContext) {
        Objects.requireNonNull(verifiedContext, "verifiedContext");
        RunEvidenceStore evidenceStore = new RunEvidenceStore(verifiedContext.traceId());
        CandidateEvidenceRegistry evidenceRegistry = new CandidateEvidenceRegistry(
                MAX_RETRIEVAL_CALLS,
                evidenceStore
        );
        /*
         * 首次检索是 Recommendation 的事实获取边界，不能由模型决定是否跳过。
         * Java 只固定“必须先有真实候选”；候选排序、选择以及是否二次检索仍由 Agent 决定。
         */
        String initialIntent = initialRetrievalIntent(userInput, verifiedContext);
        RetrievalToolResult initialCandidates = retrievalTool.searchActivities(
                initialIntent,
                verifiedContext,
                evidenceRegistry
        );
        ReActAgent agent = agentBuilder.build(verifiedContext);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(String.valueOf(verifiedContext.userId()))
                .sessionId(verifiedContext.sessionId())
                .put(VerifiedRequestContext.class, verifiedContext)
                .put(CandidateEvidenceRegistry.class, evidenceRegistry)
                .put(
                        AgentDecisionToolContext.class,
                        AgentDecisionToolContext.recommendation(verifiedContext, evidenceRegistry)
                )
                .build();
        String prompt = buildUserPrompt(userInput, verifiedContext, initialCandidates);

        traceService.recordEvent(
                "RECOMMENDATION_AGENT_STARTED",
                "AGENT",
                verifiedContext.userGoal(),
                null
        );
        try {
            RecommendationDecision decision = callDecision(agent, prompt, runtimeContext);
            if (needsServerRetry(decision) && evidenceRegistry.retrievalCalls() < MAX_RETRIEVAL_CALLS) {
                RetrievalToolResult retryCandidates = retrievalTool.searchActivities(
                        retryRetrievalIntent(userInput, verifiedContext),
                        verifiedContext,
                        evidenceRegistry
                );
                traceService.recordEvent(
                        "RECOMMENDATION_AGENT_RETRY_REQUESTED",
                        "AGENT",
                        decision,
                        java.util.Map.of(
                                "retrievalCalls", evidenceRegistry.retrievalCalls(),
                                "retryCandidates", retryCandidates
                        )
                );
                decision = callDecision(
                        agent,
                        buildRetryPrompt(verifiedContext, retryCandidates),
                        runtimeContext
                );
            }
            RecommendationDecision validated = decisionValidator.validate(decision, evidenceRegistry);
            List<ActivityItem> selected = evidenceValidator.validateRecommendation(
                    validated.selectedActivityIds(),
                    evidenceStore
            );
            RecommendationExecutionResult result = new RecommendationExecutionResult(
                    validated,
                    selected,
                    evidenceRegistry.retrievalCalls(),
                    evidenceRegistry.rounds()
            );
            traceService.recordEvent(
                    "RECOMMENDATION_DECIDED",
                    "AGENT",
                    java.util.Map.of(
                            "rounds", evidenceRegistry.rounds(),
                            "evidence", evidenceStore.snapshot()
                    ),
                    result
            );
            return result;
        } catch (RuntimeException error) {
            traceService.recordError(
                    "RECOMMENDATION_AGENT_FAILED",
                    "AGENT",
                    java.util.Map.of(
                            "rounds", evidenceRegistry.rounds(),
                            "evidence", evidenceStore.snapshot()
                    ),
                    error
            );
            throw error;
        }
    }

    String buildUserPrompt(String userInput,
                           VerifiedRequestContext context,
                           RetrievalToolResult initialCandidates) {
        String safeInput = userInput == null ? "" : userInput.trim();
        return """
                用户原话：%s
                UserGoal：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s
                服务器已执行首次 search_activities，真实候选快照：%s

                首次候选已经绑定到当前 Run Evidence，请直接基于这些 activityId 做软目标权衡。
                如果第一批候选整体无法覆盖核心 UserGoal，可以调用 search_activities 再检索一次；
                硬约束由服务器固定，不要尝试修改。最终只能从本轮 Tool 已返回的 activityId 中选择。
                """.formatted(
                safeInput,
                context.userGoal(),
                context.effectiveSlots(),
                context.hardConstraints(),
                initialCandidates
        );
    }

    private RecommendationDecision callDecision(ReActAgent agent,
                                                  String prompt,
                                                  RuntimeContext runtimeContext) {
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

    private boolean needsServerRetry(RecommendationDecision decision) {
        if (decision == null) return true;
        List<Long> selected = decision.selectedActivityIds();
        return !decision.candidatePoolSufficient()
                || selected == null
                || selected.stream().filter(Objects::nonNull).findAny().isEmpty();
    }

    private String buildRetryPrompt(VerifiedRequestContext context,
                                    RetrievalToolResult retryCandidates) {
        return """
                上一轮你没有形成可提交的最终推荐，因此服务器已执行本轮唯一一次补充检索。
                UserGoal：%s
                当前已生效槽位：%s
                补充候选快照：%s

                现在必须在本轮已经暴露的真实 activityId 中做 best-effort 最终选择。
                只要候选池非空，就选择 1~3 个最符合核心 UserGoal 的候选，并将 candidatePoolSufficient 设为 true。
                不要返回空 selectedActivityIds，不得引用未暴露 activityId，也不要补充未知事实。
                """.formatted(
                context.userGoal(),
                context.effectiveSlots(),
                retryCandidates
        );
    }

    private RecommendationDecision structuredDecision(Msg response) {
        if (response == null) {
            throw new IllegalStateException("RecommendationAgent 返回为空");
        }
        RecommendationDecision decision = response.getStructuredData(RecommendationDecision.class);
        if (decision == null) {
            throw new IllegalStateException("RecommendationAgent 未返回结构化 RecommendationDecision");
        }
        return decision;
    }

    private String initialRetrievalIntent(String userInput, VerifiedRequestContext context) {
        String safeInput = userInput == null ? "" : userInput.trim();
        if (!context.userGoal().isEmpty()) {
            return context.userGoal().toString();
        }
        return safeInput;
    }

    private String retryRetrievalIntent(String userInput, VerifiedRequestContext context) {
        String safeInput = userInput == null ? "" : userInput.trim();
        return safeInput
                + "；优先补充与核心体验目标匹配、且与首轮候选有差异的活动。UserGoal="
                + context.userGoal();
    }
}

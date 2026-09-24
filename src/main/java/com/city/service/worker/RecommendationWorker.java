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
        RuntimeContext explorerRuntime = RuntimeContext.builder()
                .userId(String.valueOf(verifiedContext.userId()))
                .sessionId(verifiedContext.sessionId())
                .put(VerifiedRequestContext.class, verifiedContext)
                .put(CandidateEvidenceRegistry.class, evidenceRegistry)
                .put(
                        AgentDecisionToolContext.class,
                        AgentDecisionToolContext.recommendation(verifiedContext, evidenceRegistry)
                )
                .build();
        RuntimeContext finalizerRuntime = RuntimeContext.builder()
                .userId(String.valueOf(verifiedContext.userId()))
                .sessionId(verifiedContext.sessionId())
                .build();

        traceService.recordEvent(
                "RECOMMENDATION_AGENT_STARTED",
                "AGENT",
                verifiedContext.userGoal(),
                null
        );
        try {
            String explorationNotes = explore(
                    agentBuilder.buildExplorer(verifiedContext),
                    buildExplorationPrompt(userInput, verifiedContext, initialCandidates),
                    explorerRuntime,
                    verifiedContext
            );
            RetrievalToolResult allCandidates = RetrievalToolResult.from(
                    "all-exposed",
                    evidenceStore.resolveActivities(evidenceRegistry.exposedActivityIds().stream().toList())
            );
            ReActAgent finalizer = agentBuilder.buildFinalizer();
            RecommendationDecision decision = callDecision(
                    finalizer,
                    buildFinalizerPrompt(userInput, verifiedContext, allCandidates, explorationNotes),
                    finalizerRuntime
            );
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
                allCandidates = RetrievalToolResult.from(
                        "all-exposed",
                        evidenceStore.resolveActivities(evidenceRegistry.exposedActivityIds().stream().toList())
                );
                decision = callDecision(
                        finalizer,
                        buildFinalizerPrompt(
                                userInput,
                                verifiedContext,
                                allCandidates,
                                explorationNotes + "\n服务器已执行唯一一次补充检索。"
                        ),
                        finalizerRuntime
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

    String buildExplorationPrompt(String userInput,
                                  VerifiedRequestContext context,
                                  RetrievalToolResult initialCandidates) {
        String safeInput = userInput == null ? "" : userInput.trim();
        return """
                用户原话：%s
                UserGoal：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s
                服务器已执行首次 search_activities，真实候选快照：%s

                判断当前候选是否足以支持 UserGoal。
                只有确有信息缺口时才调用 Tool；完成后给 Finalizer 一段简短探索摘要。
                """.formatted(
                safeInput,
                context.userGoal(),
                context.effectiveSlots(),
                context.hardConstraints(),
                initialCandidates
        );
    }

    private String explore(ReActAgent explorer,
                           String prompt,
                           RuntimeContext runtimeContext,
                           VerifiedRequestContext context) {
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
                    "RECOMMENDATION_EXPLORATION_DEGRADED",
                    "AGENT",
                    java.util.Map.of("userGoal", context.userGoal()),
                    error
            );
            return "";
        }
    }

    private String buildFinalizerPrompt(String userInput,
                                        VerifiedRequestContext context,
                                        RetrievalToolResult allCandidates,
                                        String explorationNotes) {
        return """
                用户原话：%s
                UserGoal：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s
                Explorer 后的完整已验证候选：%s
                Explorer 摘要：%s

                现在只做最终推荐结构化决策。
                事实以候选快照为准；只能选择其中真实 activityId。
                只要候选非空，就在这些候选中 best-effort 选择 1~3 个，并将 candidatePoolSufficient 设为 true。
                不得调用 Tool，不得补充候选之外事实。
                """.formatted(
                userInput == null ? "" : userInput.trim(),
                context.userGoal(),
                context.effectiveSlots(),
                context.hardConstraints(),
                allCandidates,
                explorationNotes == null ? "" : explorationNotes
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

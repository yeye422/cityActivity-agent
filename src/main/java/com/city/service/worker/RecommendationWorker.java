package com.city.service.worker;

import com.city.agent.builder.RecommendationAgentBuilder;
import com.city.model.ActivityItem;
import com.city.model.agent.RecommendationDecision;
import com.city.model.agent.RecommendationExecutionResult;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.evidence.CandidateEvidenceRegistry;
import com.city.service.evidence.DecisionEvidenceValidator;
import com.city.service.evidence.RunEvidenceStore;
import com.city.service.recommend.RecommendationDecisionValidator;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.ReActAgent;
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
    private final DecisionEvidenceValidator evidenceValidator = new DecisionEvidenceValidator();
    private final AgentTraceService traceService;

    public RecommendationWorker(RecommendationAgentBuilder agentBuilder,
                                RecommendationDecisionValidator decisionValidator,
                                AgentTraceService traceService) {
        this.agentBuilder = Objects.requireNonNull(agentBuilder, "agentBuilder");
        this.decisionValidator = Objects.requireNonNull(decisionValidator, "decisionValidator");
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
        ReActAgent agent = agentBuilder.build(verifiedContext, evidenceRegistry);
        String prompt = buildUserPrompt(userInput, verifiedContext);

        traceService.recordEvent(
                "RECOMMENDATION_AGENT_STARTED",
                "AGENT",
                verifiedContext.userGoal(),
                null
        );
        try {
            Msg response = agent.call(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(prompt)
                            .build(),
                    RecommendationDecision.class
            ).block();
            if (response == null) {
                throw new IllegalStateException("RecommendationAgent 返回为空");
            }
            RecommendationDecision decision = response.getStructuredData(RecommendationDecision.class);
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

    String buildUserPrompt(String userInput, VerifiedRequestContext context) {
        String safeInput = userInput == null ? "" : userInput.trim();
        return """
                用户原话：%s
                UserGoal：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s

                请自主决定检索意图并调用 search_activities。
                如果第一批候选整体无法覆盖核心 UserGoal，可以调整软检索意图再检索一次；
                硬约束由服务器固定，不要尝试修改。最终只从 Tool 曾返回的 activityId 中选择。
                """.formatted(
                safeInput,
                context.userGoal(),
                context.effectiveSlots(),
                context.hardConstraints()
        );
    }
}

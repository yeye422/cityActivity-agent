package com.city.service.worker;

import com.city.agent.builder.RecommendationAgentBuilder;
import com.city.model.agent.RecommendationDecision;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.evidence.CandidateEvidenceRegistry;
import com.city.service.recommend.RecommendationDecisionValidator;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * RecommendationAgent 的执行边界。
 *
 * <p>当前阶段作为新链路旁路能力存在，不写 SessionState，也不替换旧 RecommendResponseAgent。
 * Agent 可以通过 search_activities 自主检索并在候选池不足时调整软检索意图再检索一次。</p>
 */
@Component
public final class RecommendationWorker {
    private static final int MAX_RETRIEVAL_CALLS = 2;

    private final RecommendationAgentBuilder agentBuilder;
    private final RecommendationDecisionValidator decisionValidator;
    private final AgentTraceService traceService;

    public RecommendationWorker(RecommendationAgentBuilder agentBuilder,
                                RecommendationDecisionValidator decisionValidator,
                                AgentTraceService traceService) {
        this.agentBuilder = Objects.requireNonNull(agentBuilder, "agentBuilder");
        this.decisionValidator = Objects.requireNonNull(decisionValidator, "decisionValidator");
        this.traceService = Objects.requireNonNull(traceService, "traceService");
    }

    public RecommendationDecision execute(String userInput,
                                          VerifiedRequestContext verifiedContext) {
        Objects.requireNonNull(verifiedContext, "verifiedContext");
        CandidateEvidenceRegistry evidenceRegistry = new CandidateEvidenceRegistry(MAX_RETRIEVAL_CALLS);
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
            traceService.recordEvent(
                    "RECOMMENDATION_DECIDED",
                    "AGENT",
                    evidenceRegistry.rounds(),
                    validated
            );
            return validated;
        } catch (RuntimeException error) {
            traceService.recordError(
                    "RECOMMENDATION_AGENT_FAILED",
                    "AGENT",
                    evidenceRegistry.rounds(),
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

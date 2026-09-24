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
import com.city.tool.RetrievalTool;
import com.city.model.tool.RetrievalToolResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        ReActAgent agent = agentBuilder.build(verifiedContext, evidenceRegistry);
        String prompt = buildUserPrompt(userInput, verifiedContext, initialCandidates);

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
                            .build()
            ).block();
            RecommendationDecision decision = parseDecision(response);
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

    private RecommendationDecision parseDecision(Msg response) {
        if (response == null) {
            throw new IllegalStateException("RecommendationAgent 返回为空");
        }
        String json = normalizeJson(response.getTextContent());
        try {
            return objectMapper.readValue(json, RecommendationDecision.class);
        } catch (Exception error) {
            throw new IllegalStateException("RecommendationAgent 最终 JSON 无法解析: " + json, error);
        }
    }

    private String normalizeJson(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstBreak >= 0 && lastFence > firstBreak) {
                text = text.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return text;
    }

    private String initialRetrievalIntent(String userInput, VerifiedRequestContext context) {
        String safeInput = userInput == null ? "" : userInput.trim();
        if (!context.userGoal().isEmpty()) {
            return context.userGoal().toString();
        }
        return safeInput;
    }
}

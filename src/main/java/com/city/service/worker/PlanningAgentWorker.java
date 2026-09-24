package com.city.service.worker;

import com.city.agent.builder.PlanningAgentBuilder;
import com.city.model.TravelTimeEvidence;
import com.city.model.agent.PlanNotebook;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.agent.PlanningDecision;
import com.city.model.agent.PlanValidationResult;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.PlanningDiscoveryToolResult;
import com.city.service.evidence.DecisionEvidenceValidator;
import com.city.service.evidence.PlanningEvidenceRegistry;
import com.city.service.evidence.RunEvidenceStore;
import com.city.service.plan.PlanProposalValidationService;
import com.city.service.plan.PlanningConstraintParser;
import com.city.service.trace.AgentTraceService;
import com.city.tool.PlanValidationTool;
import com.city.tool.PlanningDiscoveryTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final PlanningDiscoveryTool discoveryTool;
    private final DecisionEvidenceValidator evidenceValidator = new DecisionEvidenceValidator();
    private final AgentTraceService traceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                null,
                traceService
        );
    }

    /** 保留测试/旧构造入口；生产环境由包含 discoveryTool 的构造器注入。 */
    public PlanningAgentWorker(
            PlanningAgentBuilder agentBuilder,
            PlanningConstraintParser constraintParser,
            PlanProposalValidationService validationService,
            PlanValidationTool validationTool,
            AgentTraceService traceService
    ) {
        this(agentBuilder, constraintParser, validationService, validationTool, null, traceService);
    }

    @Autowired
    public PlanningAgentWorker(
            PlanningAgentBuilder agentBuilder,
            PlanningConstraintParser constraintParser,
            PlanProposalValidationService validationService,
            PlanValidationTool validationTool,
            PlanningDiscoveryTool discoveryTool,
            AgentTraceService traceService
    ) {
        this.agentBuilder = Objects.requireNonNull(agentBuilder, "agentBuilder");
        this.constraintParser = Objects.requireNonNull(constraintParser, "constraintParser");
        this.validationService = Objects.requireNonNull(validationService, "validationService");
        this.validationTool = Objects.requireNonNull(validationTool, "validationTool");
        this.discoveryTool = discoveryTool;
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
        /*
         * 先由服务器确定性执行一次 discovery，把候选与 Evidence/Notebook 绑定到当前 Run。
         * 这不是旧 Worker fallback：PlanningAgent 仍负责选择/组合，Solver 仍负责最终合法性。
         * 预加载只消除“模型第一步 discovery 后上下文丢失/直接 structured response”的不稳定性。
         */
        PlanningDiscoveryToolResult preloadedCandidates = discoveryTool == null
                ? null
                : discoveryTool.discover(planningContext);
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
                            .textContent(buildPrompt(userInput, verifiedContext, safeWindows, preloadedCandidates))
                            .build()
            ).block();
            PlanningDecision decision = structuredDecision(response);

            /*
             * AgentScope 的 generate_response 是框架级结构化输出 Tool，不能在 Hook 中通过抛异常阻止。
             * 若模型提前提交 proposal，则在 Worker 边界使用同一个 validate_plan Tool 补验。
             * 第一次补验为 invalid 时，把 violations/notebook 显式反馈给同一个 Agent，再允许一次
             * repair -> revalidate；只有最终 validated 的 proposal 才能进入业务响应。
             */
            try {
                ensureToolValidated(decision, planningContext);
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
                Msg repairedResponse = agent.call(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent(buildRepairPrompt(planningContext))
                                .build()
                ).block();
                decision = structuredDecision(repairedResponse);
                ensureToolValidated(decision, planningContext);
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

    /** package-private：便于回归测试 PlanningAgent 提前 generate_response 的补验边界。 */
    void ensureToolValidated(PlanningDecision decision, PlanningToolContext planningContext) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(planningContext, "planningContext");
        if (planningContext.notebook().validated()) {
            return;
        }

        PlanValidationResult lateValidation = validationTool.validate(decision.plan(), planningContext);
        if (!lateValidation.valid()) {
            throw new IllegalStateException(
                    "PlanningAgent 最终方案补验失败: " + lateValidation.violations());
        }
    }

    private PlanningDecision structuredDecision(Msg response) {
        if (response == null) {
            throw new IllegalStateException("PlanningAgent 返回为空");
        }
        String json = normalizeJson(response.getTextContent());
        try {
            return objectMapper.readValue(json, PlanningDecision.class);
        } catch (Exception error) {
            throw new IllegalStateException("PlanningAgent 最终 JSON 无法解析: " + json, error);
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

    private String buildPrompt(String userInput,
                               VerifiedRequestContext verifiedContext,
                               List<String> windows,
                               PlanningDiscoveryToolResult preloadedCandidates) {
        return """
                用户原话：%s
                UserGoal：%s
                规划窗口：%s
                当前已生效槽位：%s
                服务器硬约束摘要：%s
                服务器已预加载候选快照：%s

                候选已经由服务器通过 discover_plan_candidates 绑定到当前 Run Evidence。
                只从上面的真实 activityId/sessionId 中选择；如确有必要可再次调用 discover_plan_candidates 刷新。
                当方案包含不同 venueId 的连续场次时，优先调用 get_travel_time 获取对应场次间的真实路线时长证据。
                现在先提交一个完整 PlanningDecision proposal；服务器会在 Java 边界强制调用 validate_plan。
                如果服务器随后返回 repair 请求，请严格根据 Notebook 中的 latestViolations/repairHint 修改冲突窗口。
                不要因为自己尚未调用 validate_plan 而返回空 plan，也不要跳过任何规划窗口。
                """.formatted(
                userInput == null ? "" : userInput.trim(),
                verifiedContext.userGoal(),
                windows,
                verifiedContext.effectiveSlots(),
                verifiedContext.hardConstraints(),
                preloadedCandidates == null ? "未预加载，请先调用 discover_plan_candidates" : preloadedCandidates
        );
    }

    private String buildRepairPrompt(PlanningToolContext planningContext) {
        return """
                上一个 proposal 没有通过服务器 validate_plan。
                当前 Notebook：%s

                请严格根据 latestViolations/repairHint 只修改冲突窗口。
                如新的连续场次跨 venueId，优先调用 get_travel_time 补齐路线证据。
                直接提交修正后的完整 PlanningDecision；服务器会再次强制调用 validate_plan。
                不要返回空 plan，也不要重复上一个已被拒绝的 proposal。
                """.formatted(planningContext.notebook().snapshot());
    }
}

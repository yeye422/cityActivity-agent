package com.city.service.orchestrator;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.model.ChatRequest;
import com.city.model.ChatResponse;
import com.city.model.IntentResult;
import com.city.model.MemoryMutationProposal;
import com.city.model.RelaxationContext;
import com.city.model.RelaxationRequest;
import com.city.model.ResponseResult;
import com.city.model.RiskGuardResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TimeResolutionResult;
import com.city.model.WeatherRecommendationContext;
import com.city.service.activity.ActivityService;
import com.city.service.activity.RelaxationSearchService;
import com.city.service.clarify.ClarifyRuleService;
import com.city.service.recommend.RecommendationResponseGeneratorService;
import com.city.service.risk.RiskGuardService;
import com.city.service.session.SessionService;
import com.city.service.slot.SlotOptionService;
import com.city.service.time.TimeResolutionService;
import com.city.service.trace.AgentTraceService;
import com.city.service.weather.WeatherRecommendationService;
import com.city.service.worker.ContextWorker;
import com.city.service.worker.MemoryWorker;
import com.city.service.workflow.WorkflowType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单轮业务运行边界。
 *
 * <p>负责把请求转换为可路由的 PreparedRun，并承接输入 Guard、时间解析、来源降级、
 * Workflow 执行与 Relaxation。它不拥有请求级锁，也不创建 Trace。</p>
 */
@Service
public class AgentRunService {

    private static final String CHITCHAT_REPLY =
            "我可以帮你推荐活动、调整条件，或者安排半天/一天的活动计划。";

    private final SessionService sessionService;
    private final SlotOptionService slotOptionService;
    private final ActivityService activityService;
    private final ContextWorker contextWorker;
    private final MemoryWorker memoryWorker;
    private final TimeResolutionService timeResolutionService;
    private final RiskGuardService riskGuardService;
    private final ClarifyRuleService clarifyRuleService;
    private final CityFlowWorkflowExecutor workflowExecutor;
    private final DecisionCommitService commitService;
    private final RelaxationSearchService relaxationSearchService;
    private final RecommendationResponseGeneratorService recommendationResponseGeneratorService;
    private final WeatherRecommendationService weatherRecommendationService;
    private final AgentTraceService traceService;

    public AgentRunService(SessionService sessionService,
                           SlotOptionService slotOptionService,
                           ActivityService activityService,
                           ContextWorker contextWorker,
                           MemoryWorker memoryWorker,
                           TimeResolutionService timeResolutionService,
                           RiskGuardService riskGuardService,
                           ClarifyRuleService clarifyRuleService,
                           CityFlowWorkflowExecutor workflowExecutor,
                           DecisionCommitService commitService,
                           RelaxationSearchService relaxationSearchService,
                           RecommendationResponseGeneratorService recommendationResponseGeneratorService,
                           WeatherRecommendationService weatherRecommendationService,
                           AgentTraceService traceService) {
        this.sessionService = sessionService;
        this.slotOptionService = slotOptionService;
        this.activityService = activityService;
        this.contextWorker = contextWorker;
        this.memoryWorker = memoryWorker;
        this.timeResolutionService = timeResolutionService;
        this.riskGuardService = riskGuardService;
        this.clarifyRuleService = clarifyRuleService;
        this.workflowExecutor = workflowExecutor;
        this.commitService = commitService;
        this.relaxationSearchService = relaxationSearchService;
        this.recommendationResponseGeneratorService = recommendationResponseGeneratorService;
        this.weatherRecommendationService = weatherRecommendationService;
        this.traceService = traceService;
    }

    public PreparedRun prepare(Long userId,
                               ChatRequest request,
                               String traceId,
                               SessionState loadedState) {
        SessionState state = applyRequestContext(loadedState, request.context());
        String sessionId = state.sessionId();
        sessionService.appendMessage(sessionId, "user", request.message(), null, traceId);
        traceService.recordEvent(
                "USER_MESSAGE_RECORDED", "SESSION", request.message(),
                Map.of("sessionId", sessionId, "sourceMode", state.sourceMode()));

        boolean publicFallbackUsed = false;
        if (state.sourceMode() == SourceMode.PERSONAL && !activityService.hasPersonalActivities(userId)) {
            traceService.recordEvent(
                    "PERSONAL_LIBRARY_EMPTY_FALLBACK_TO_PUBLIC", "ROUTE",
                    Map.of("userId", userId, "originalSourceMode", SourceMode.PERSONAL),
                    Map.of("fallbackSourceMode", SourceMode.PUBLIC));
            state = state.withSourceMode(SourceMode.PUBLIC);
            publicFallbackUsed = true;
        }

        ContextWorker.Result contextResult = contextWorker.understand(
                sessionId,
                userId,
                request.message(),
                state,
                sessionService.recentConversationTurns(sessionId, userId, 3));
        IntentResult rawIntent = contextResult.raw();
        IntentResult intent = contextResult.revised();
        traceService.recordEvent("INTENT_RECOGNIZED", "INTENT", request.message(), rawIntent);
        traceService.recordEvent("INTENT_REVISED", "INTENT", rawIntent, intent);

        RiskGuardResult inputGuard = riskGuardService.checkInput(request.message());
        traceService.recordEvent(
                "RISK_GUARD_INPUT_CHECKED", "GUARD",
                Map.of("intent", intent.intent(), "userInput", request.message()), inputGuard);
        if (!inputGuard.passed()) {
            ResponseResult safeResponse = ResponseResult.textOnly(inputGuard.rewriteSuggestion());
            traceService.recordEvent("RISK_GUARD_INPUT_BLOCKED", "GUARD", inputGuard, safeResponse);
            ChatResponse terminal = commitService.commitText(
                    traceId, state, state.currentIntent(), safeResponse, true);
            return new PreparedRun(state, intent, publicFallbackUsed, terminal);
        }

        if (isActivityFlow(intent.intent()) && intent.userGoalPatch() != null) {
            state = state.withUserGoals(intent.userGoalPatch().apply(state.userGoals()));
            traceService.recordEvent("USER_GOAL_PATCH_APPLIED", "CONTEXT",
                    intent.userGoalPatch(), state.userGoals());
        }

        persistMemoryProposals(userId, intent.memoryProposals());

        TimeResolutionResult timeResolution = timeResolutionService.resolve(
                state.timeConstraint(), intent.temporal(), request.message());
        traceService.recordEvent("TIME_RESOLUTION_DECIDED", "TIME", intent.temporal(), timeResolution);
        if (timeResolution.needsClarification() && isActivityFlow(intent.intent())) {
            SessionState clarifyState = state.withIntent(intent.intent());
            ChatResponse terminal = commitService.commitClarification(
                    traceId,
                    clarifyState,
                    ClarifyField.TIME,
                    clarifyRuleService.questionFor(ClarifyField.TIME));
            return new PreparedRun(clarifyState, intent, publicFallbackUsed, terminal);
        }
        if (timeResolution.shouldUpdateState()) {
            state = state.withTimeConstraint(timeResolution.timeConstraint());
            traceService.recordEvent(
                    "TIME_CONSTRAINT_UPDATED", "TIME", intent.temporal(), timeResolution.timeConstraint());
        }

        return new PreparedRun(state, intent, publicFallbackUsed, null);
    }

    public ChatResponse execute(String userInput,
                                String traceId,
                                PreparedRun run,
                                WorkflowType workflow) {
        traceService.recordEvent(
                "ROUTE_SELECTED", "ROUTE", run.intent(),
                Map.of("intent", run.intent().intent(), "workflow", workflow));

        return switch (workflow) {
            case RECOMMEND -> workflowExecutor.recommend(
                    userInput, traceId, run.state(), run.intent(), run.publicFallbackUsed());
            case ADJUST -> workflowExecutor.adjust(
                    userInput, traceId, run.state(), run.intent(), run.publicFallbackUsed());
            case PLAN -> workflowExecutor.plan(
                    userInput, traceId, run.state(), run.intent(), run.publicFallbackUsed());
            case CHITCHAT -> handleChitchat(traceId, run.state());
        };
    }

    public ChatResponse executeRelaxation(Long userId,
                                          RelaxationRequest request,
                                          String traceId,
                                          SessionState loadedState) {
        RelaxationContext context = loadedState.pendingRelaxationContext();
        if (context == null || context.sourceMode() == null) {
            throw new CityException("相近活动方案已失效，请重新发起推荐");
        }
        if (!context.availableLevels().contains(request.level())) {
            throw new CityException("无效的相近活动方案，请重新获取可选方案");
        }

        SessionState effectiveState = loadedState.withSourceMode(context.sourceMode());
        String currentQueryKey = DecisionCommitService.recommendationQueryKey(effectiveState);
        if (!context.queryKey().equals(currentQueryKey)) {
            throw new CityException("推荐条件已变化，请重新获取相近活动方案");
        }

        RelaxationSearchService.SearchResult result = relaxationSearchService.find(
                context.sourceMode(),
                userId,
                effectiveState.slots(),
                effectiveState.excludedSlots(),
                context.excludeActivityIds(),
                effectiveState.timeConstraint(),
                request.level());
        if (result.ranked().isEmpty()) {
            throw new CityException("该相近活动方案暂时没有结果，请重新获取可选方案");
        }
        traceService.recordEvent(
                "RELAXATION_SELECTED", "SEARCH", request,
                Map.of("level", result.level(),
                        "sourceMode", context.sourceMode(),
                        "queryKey", context.queryKey(),
                        "relaxedSlots", result.relaxedSlots(),
                        "candidateCount", result.ranked().size()));

        SessionState selectedState = effectiveState
                .withPendingRelaxationContext(null)
                .withPendingClarifyField(null)
                .withIntent(Intent.ACTIVITY_RECOMMENDATION);
        WeatherRecommendationContext weather = weatherRecommendationService.resolve(
                selectedState.slots(), selectedState.timeConstraint());
        var generated = recommendationResponseGeneratorService.generateRanked(
                result.ranked(),
                "没有完全匹配的活动，已" + result.label() + "。以下结果按原始需求的接近程度排序。",
                weather);
        return commitService.commitDecision(
                "查看相近活动：" + result.label(), traceId, selectedState, generated, false);
    }

    public void validateChatRequest(ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new CityException("用户问题不能为空");
        }
        if (request.sourceMode() == null) {
            throw new CityException("sourceMode 不能为空，请选择 PERSONAL 或 PUBLIC");
        }
    }

    public void validateRelaxationRequest(RelaxationRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()
                || request.level() == null) {
            throw new CityException("相近活动请求参数不完整");
        }
    }

    private ChatResponse handleChitchat(String traceId, SessionState state) {
        boolean preserveBusinessState = hasPendingClarification(state);
        return commitService.commitText(
                traceId,
                state,
                Intent.OTHER,
                ResponseResult.textOnly(CHITCHAT_REPLY),
                preserveBusinessState);
    }

    private SessionState applyRequestContext(SessionState state, Map<String, Object> context) {
        SlotBundle contextSlots = contextSlots(context);
        if (contextSlots.isEmpty()) return state;

        SlotBundle historical = state.slots() == null ? SlotBundle.empty() : state.slots();
        SlotBundle applied = new SlotBundle(
                contextSlots.city().isEmpty() ? historical.city() : contextSlots.city(),
                contextSlots.location().isEmpty() ? historical.location() : contextSlots.location(),
                historical.experienceGoal(),
                historical.companion(),
                historical.budget(),
                historical.activityType(),
                historical.style(),
                historical.duration(),
                historical.feature());
        Set<String> unconstrained = new LinkedHashSet<>(
                state.unconstrainedSlots() == null ? Set.of() : state.unconstrainedSlots());
        if (!contextSlots.city().isEmpty()) unconstrained.remove("city");
        if (!contextSlots.location().isEmpty()) unconstrained.remove("location");
        SessionState updated = state.withSlots(applied).withUnconstrainedSlots(unconstrained);
        traceService.recordEvent("CONTEXT_SLOTS_APPLIED", "SLOT", context, contextSlots);
        return updated;
    }

    private SlotBundle contextSlots(Map<String, Object> context) {
        if (context == null || context.isEmpty()) return SlotBundle.empty();
        String city = contextValue(context, "city");
        String location = contextValue(context, "location");
        return slotOptionService.sanitize(new SlotBundle(
                city.isBlank() ? List.of() : List.of(city),
                location.isBlank() ? List.of() : List.of(location),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    private String contextValue(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void persistMemoryProposals(Long userId, List<MemoryMutationProposal> proposals) {
        if (proposals == null || proposals.isEmpty()) return;
        for (MemoryMutationProposal proposal : proposals) {
            try {
                var stored = memoryWorker.rememberConfirmedAgentPreference(userId, proposal);
                traceService.recordEvent(
                        "MEMORY_PROPOSAL_COMMITTED",
                        "MEMORY",
                        proposal,
                        Map.of("memoryId", stored == null ? "" : String.valueOf(stored.getId()))
                );
            } catch (RuntimeException error) {
                traceService.recordEvent(
                        "MEMORY_PROPOSAL_REJECTED",
                        "MEMORY",
                        proposal,
                        Map.of("reason", String.valueOf(error.getMessage()))
                );
            }
        }
    }

    private boolean hasPendingClarification(SessionState state) {
        return state != null
                && state.phase() == SessionPhase.CLARIFY
                && state.pendingClarifyField() != null
                && state.currentIntent() != null;
    }

    private boolean isActivityFlow(Intent intent) {
        return intent == Intent.ACTIVITY_RECOMMENDATION
                || intent == Intent.ACTIVITY_ADJUST
                || intent == Intent.ACTIVITY_PLAN;
    }

    public record PreparedRun(
            SessionState state,
            IntentResult intent,
            boolean publicFallbackUsed,
            ChatResponse terminalResponse
    ) {}
}

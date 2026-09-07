package com.city.service.orchestrator;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.model.ActivityDiversityResult;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivityRankResult;
import com.city.model.ActivitySearchRequest;
import com.city.model.ChatRequest;
import com.city.model.ChatResponse;
import com.city.model.IntentResult;
import com.city.model.RecommendResult;
import com.city.model.RelaxationContext;
import com.city.model.RelaxationOption;
import com.city.model.RelaxationRequest;
import com.city.model.ResponseResult;
import com.city.model.RiskGuardResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.SlotMutation;
import com.city.model.TimeConstraint;
import com.city.model.TimeResolutionResult;
import com.city.model.WeatherRecommendationContext;
import com.city.service.activity.ActivityDiversityService;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import com.city.service.activity.ActivityService;
import com.city.service.activity.RelaxationSearchService;
import com.city.service.clarify.ClarifyRuleService;
import com.city.service.intent.IntentAgentService;
import com.city.service.intent.IntentReviseService;
import com.city.service.plan.ActivityPlanService;
import com.city.service.plan.PlanResponseAgentService;
import com.city.service.recommend.RecommendResponseAgentService;
import com.city.service.risk.RiskGuardService;
import com.city.service.session.SessionService;
import com.city.service.session.SessionStateService;
import com.city.service.slot.SlotMutationService;
import com.city.service.slot.SlotOptionService;
import com.city.service.time.TimeResolutionService;
import com.city.service.trace.AgentTraceService;
import com.city.service.weather.WeatherRecommendationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 城市活动多 Agent 编排服务。 */
@Service
public class CityOrchestratorService {

    private static final String CHITCHAT_REPLY = "我是城市周末活动助手，帮你发现周末好去处。你可以告诉我时间、预算、想要的氛围，比如「周六和朋友，预算200以内，想放松」。";

    private final SessionService sessionService;
    private final SessionStateService sessionStateService;
    private final IntentAgentService intentAgentService;
    private final IntentReviseService intentReviseService;
    private final SlotOptionService slotOptionService;
    private final SlotMutationService slotMutationService;
    private final ClarifyRuleService clarifyRuleService;
    private final ActivitySearchService activitySearchService;
    private final ActivityRankService activityRankService;
    private final ActivityDiversityService activityDiversityService;
    private final RecommendResponseAgentService recommendResponseAgentService;
    private final ActivityPlanService activityPlanService;
    private final PlanResponseAgentService planResponseAgentService;
    private final ActivityService activityService;
    private final RelaxationSearchService relaxationSearchService;
    private final WeatherRecommendationService weatherRecommendationService;
    private final TimeResolutionService timeResolutionService;
    private final RiskGuardService riskGuardService;
    private final AgentTraceService agentTraceService;
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public CityOrchestratorService(
            SessionService sessionService,
            SessionStateService sessionStateService,
            IntentAgentService intentAgentService,
            IntentReviseService intentReviseService,
            SlotOptionService slotOptionService,
            SlotMutationService slotMutationService,
            ClarifyRuleService clarifyRuleService,
            ActivitySearchService activitySearchService,
            ActivityRankService activityRankService,
            ActivityDiversityService activityDiversityService,
            RecommendResponseAgentService recommendResponseAgentService,
            ActivityPlanService activityPlanService,
            PlanResponseAgentService planResponseAgentService,
            ActivityService activityService,
            RelaxationSearchService relaxationSearchService,
            WeatherRecommendationService weatherRecommendationService,
            TimeResolutionService timeResolutionService,
            RiskGuardService riskGuardService,
            AgentTraceService agentTraceService
    ) {
        this.sessionService = sessionService;
        this.sessionStateService = sessionStateService;
        this.intentAgentService = intentAgentService;
        this.intentReviseService = intentReviseService;
        this.slotOptionService = slotOptionService;
        this.slotMutationService = slotMutationService;
        this.clarifyRuleService = clarifyRuleService;
        this.activitySearchService = activitySearchService;
        this.activityRankService = activityRankService;
        this.activityDiversityService = activityDiversityService;
        this.recommendResponseAgentService = recommendResponseAgentService;
        this.activityPlanService = activityPlanService;
        this.planResponseAgentService = planResponseAgentService;
        this.activityService = activityService;
        this.relaxationSearchService = relaxationSearchService;
        this.weatherRecommendationService = weatherRecommendationService;
        this.timeResolutionService = timeResolutionService;
        this.riskGuardService = riskGuardService;
        this.agentTraceService = agentTraceService;
    }

    public ChatResponse dietChat(Long userId, ChatRequest request) {
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new CityException("用户问题不能为空");
        }
        if (request.sourceMode() == null) {
            throw new CityException("sourceMode 不能为空，请选择 PERSONAL 或 PUBLIC");
        }

        SessionState initialState = sessionStateService.loadOrCreate(request.sessionId(), userId, request.sourceMode());
        try (AgentTraceService.TraceScope ignored = agentTraceService.openTrace(traceId, initialState.sessionId(), userId)) {
            try {
                long startedAt = System.nanoTime();
                agentTraceService.recordEvent("REQUEST_RECEIVED", "HTTP", request, initialState);
                Object lock = sessionLocks.computeIfAbsent(initialState.sessionId(), key -> new Object());
                synchronized (lock) {
                    ChatResponse response = handleTurn(userId, request, traceId, initialState);
                    agentTraceService.recordEvent("REQUEST_FINISHED", "HTTP", request, response, elapsedMs(startedAt));
                    return response;
                }
            } catch (RuntimeException error) {
                agentTraceService.recordError("REQUEST_FAILED", "HTTP", request, error);
                throw error;
            }
        }
    }

    public ChatResponse showRelaxedRecommendation(Long userId, RelaxationRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank() || request.level() == null) {
            throw new CityException("相近活动请求参数不完整");
        }
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        SessionState loadedState = sessionStateService.loadExisting(request.sessionId(), userId);
        try (AgentTraceService.TraceScope ignored = agentTraceService.openTrace(traceId, loadedState.sessionId(), userId)) {
            Object lock = sessionLocks.computeIfAbsent(loadedState.sessionId(), key -> new Object());
            synchronized (lock) {
                RelaxationContext context = loadedState.pendingRelaxationContext();
                if (context == null || context.sourceMode() == null) {
                    throw new CityException("相近活动方案已失效，请重新发起推荐");
                }
                if (!context.availableLevels().contains(request.level())) {
                    throw new CityException("无效的相近活动方案，请重新获取可选方案");
                }

                SessionState effectiveState = loadedState.withSourceMode(context.sourceMode());
                String currentQueryKey = recommendationQueryKey(effectiveState);
                if (!context.queryKey().equals(currentQueryKey)) {
                    throw new CityException("推荐条件已变化，请重新获取相近活动方案");
                }

                RelaxationSearchService.SearchResult result = relaxationSearchService.find(
                        context.sourceMode(), userId, effectiveState.slots(), effectiveState.excludedSlots(),
                        context.excludeActivityIds(), effectiveState.timeConstraint(), request.level());
                agentTraceService.recordEvent("RELAXATION_SELECTED", "SEARCH", request,
                        traceMap("level", result.level(),
                                "sourceMode", context.sourceMode(),
                                "queryKey", context.queryKey(),
                                "excludeActivityIds", context.excludeActivityIds(),
                                "relaxedSlots", result.relaxedSlots(),
                                "querySlots", result.querySlots(),
                                "excludedSlots", effectiveState.excludedSlots(),
                                "candidateCount", result.ranked().size()));
                if (result.ranked().isEmpty()) {
                    throw new CityException("该相近活动方案暂时没有结果，请重新获取可选方案");
                }

                SessionState selectedState = effectiveState.withPendingRelaxationContext(null)
                        .withPendingClarifyField(null);
                return completeRecommendation(
                        selectedState.sessionId(), userId, "查看相近活动：" + result.label(), traceId,
                        selectedState, context.excludeActivityIds(), result);
            }
        }
    }

    private ChatResponse handleTurn(Long userId, ChatRequest request, String traceId, SessionState state) {
        String sessionId = state.sessionId();
        SlotBundle contextSlots = contextSlots(request.context());
        if (!contextSlots.isEmpty()) {
            state = applyContextSlots(state, contextSlots);
            agentTraceService.recordEvent("CONTEXT_SLOTS_APPLIED", "SLOT", request.context(), contextSlots);
        }
        SourceMode sourceMode = state.sourceMode();
        sessionService.appendMessage(sessionId, "user", request.message(), null, traceId);
        agentTraceService.recordEvent("USER_MESSAGE_RECORDED", "SESSION", request.message(), Map.of("sessionId", sessionId, "sourceMode", sourceMode));

        boolean publicFallbackUsed = false;
        if (sourceMode == SourceMode.PERSONAL && !activityService.hasPersonalActivities(userId)) {
            agentTraceService.recordEvent("PERSONAL_LIBRARY_EMPTY_FALLBACK_TO_PUBLIC", "ROUTE",
                    Map.of("userId", userId, "originalSourceMode", sourceMode),
                    Map.of("fallbackSourceMode", SourceMode.PUBLIC));
            state = state.withSourceMode(SourceMode.PUBLIC);
            publicFallbackUsed = true;
        }

        IntentResult rawIntent = intentAgentService.recognize(
                sessionId,
                userId,
                request.message(),
                state.slots(),
                state.timeConstraint(),
                sessionService.recentConversationTurns(sessionId, userId, 3)
        );
        agentTraceService.recordEvent("INTENT_RECOGNIZED", "INTENT", request.message(), rawIntent);

        IntentResult intent = intentReviseService.revise(state, rawIntent, request.message());
        agentTraceService.recordEvent("INTENT_REVISED", "INTENT", rawIntent, intent);

        RiskGuardResult inputGuard = riskGuardService.checkInput(request.message());
        agentTraceService.recordEvent("RISK_GUARD_INPUT_CHECKED", "GUARD",
                traceMap("intent", intent.intent(), "userInput", request.message()), inputGuard);
        if (!inputGuard.passed()) {
            ResponseResult safeResponse = ResponseResult.textOnly(inputGuard.rewriteSuggestion());
            agentTraceService.recordEvent("RISK_GUARD_INPUT_BLOCKED", "GUARD", inputGuard, safeResponse);
            agentTraceService.recordEvent("ROUTE_BYPASSED_BY_RISK_GUARD", "ROUTE",
                    traceMap("intent", intent.intent()), Map.of("blocked", true));
            return completeGuardedTextOnly(sessionId, traceId, state, safeResponse);
        }

        TimeResolutionResult timeResolution = timeResolutionService.resolve(
                state.timeConstraint(), intent.temporal(), request.message());
        agentTraceService.recordEvent("TIME_RESOLUTION_DECIDED", "TIME", intent.temporal(), timeResolution);

        if (timeResolution.needsClarification() && isActivityFlow(intent.intent())) {
            agentTraceService.recordEvent("TIME_PARSE_CLARIFY", "TIME", request.message(), timeResolution);
            SessionState clarifyState = state.withIntent(intent.intent());
            agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY",
                    traceMap("timeResolution", timeResolution), clarifyDecisionPayload(ClarifyField.TIME));
            return completeAsk(sessionId, traceId, clarifyState,
                    ClarifyField.TIME, clarifyRuleService.questionFor(ClarifyField.TIME));
        }

        if (timeResolution.shouldUpdateState()) {
            state = state.withTimeConstraint(timeResolution.timeConstraint());
            if (timeResolution.status() == TimeResolutionResult.Status.JAVA_FALLBACK) {
                agentTraceService.recordEvent("TIME_PARSE_FALLBACK", "TIME", request.message(), timeResolution.timeConstraint());
            } else if (timeResolution.status() == TimeResolutionResult.Status.CLEAR) {
                agentTraceService.recordEvent("TIME_CONSTRAINT_CLEARED", "TIME", request.message(), timeResolution.timeConstraint());
            } else {
                agentTraceService.recordEvent("TIME_CONSTRAINT_RESOLVED", "TIME", intent.temporal(), timeResolution.timeConstraint());
            }
        }

        boolean fallbackParsingUsed = intent.fallback()
                || timeResolution.status() == TimeResolutionResult.Status.JAVA_FALLBACK;
        agentTraceService.recordEvent("ROUTE_SELECTED", "ROUTE", intent,
                traceMap("route", intent.intent(), "fallbackParsingUsed", fallbackParsingUsed));

        return switch (intent.intent()) {
            case MEAL_RECOMMENDATION ->
                    handleRecommendation(sessionId, userId, request.message(), traceId, state, intent,
                            publicFallbackUsed, fallbackParsingUsed);
            case MEAL_ADJUST -> handleAdjust(sessionId, userId, request.message(), traceId, state, intent,
                    publicFallbackUsed, fallbackParsingUsed);
            case ACTIVITY_PLAN -> handlePlan(sessionId, userId, request.message(), traceId, state, intent,
                    publicFallbackUsed, fallbackParsingUsed);
            case OTHER -> handleChitchat(sessionId, traceId, state);
        };
    }

    private boolean isActivityFlow(Intent intent) {
        return intent == Intent.MEAL_RECOMMENDATION
                || intent == Intent.MEAL_ADJUST
                || intent == Intent.ACTIVITY_PLAN;
    }

    private SlotBundle contextSlots(Map<String, Object> context) {
        if (context == null || context.isEmpty()) return SlotBundle.empty();
        String city = contextValue(context, "city");
        String location = contextValue(context, "location");
        return slotOptionService.sanitize(new SlotBundle(
                city.isBlank() ? List.of() : List.of(city),
                location.isBlank() ? List.of() : List.of(location),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
    }

    private SessionState applyContextSlots(SessionState state, SlotBundle contextSlots) {
        SlotBundle historical = state.slots() == null ? SlotBundle.empty() : state.slots();
        SlotBundle context = contextSlots == null ? SlotBundle.empty() : contextSlots;
        SlotBundle applied = new SlotBundle(
                context.city().isEmpty() ? historical.city() : context.city(),
                context.location().isEmpty() ? historical.location() : context.location(),
                historical.experienceGoal(), historical.companion(), historical.budget(), historical.activityType(),
                historical.style(), historical.duration(), historical.feature()
        );
        Set<String> unconstrained = new LinkedHashSet<>(
                state.unconstrainedSlots() == null ? Set.of() : state.unconstrainedSlots());
        if (!context.city().isEmpty()) unconstrained.remove("city");
        if (!context.location().isEmpty()) unconstrained.remove("location");
        return state.withSlots(applied).withUnconstrainedSlots(unconstrained);
    }

    private String contextValue(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private SlotMutation applySlotMutation(SessionState state, IntentResult intent) {
        SlotMutation mutation = slotMutationService.apply(
                intent.operations(), state.slots(), state.excludedSlots(), state.unconstrainedSlots());
        agentTraceService.recordEvent("SLOT_MUTATION_APPLIED", "SLOT",
                traceMap("stateSlots", state.slots(),
                        "stateExcludedSlots", state.excludedSlots(),
                        "stateUnconstrainedSlots", state.unconstrainedSlots(),
                        "operations", intent.operations()),
                traceMap("resultSlots", mutation.included(),
                        "resultExcludedSlots", mutation.excluded(),
                        "resultUnconstrainedSlots", mutation.unconstrained()));
        return mutation;
    }

    private ChatResponse handleRecommendation(String sessionId, Long userId, String userInput, String traceId,
                                              SessionState state, IntentResult intent, boolean publicFallbackUsed,
                                              boolean fallbackParsingUsed) {
        SlotMutation mutation = applySlotMutation(state, intent);
        SlotBundle mergedSlots = mutation.included();
        SessionState workingState = state.withIntent(Intent.MEAL_RECOMMENDATION)
                .withSlots(mergedSlots)
                .withExcludedSlots(mutation.excluded())
                .withUnconstrainedSlots(mutation.unconstrained());
        ClarifyField missingField = firstMissingRequiredField(Intent.MEAL_RECOMMENDATION, workingState);
        agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY",
                traceMap("slots", mergedSlots,
                        "timeConstraint", workingState.timeConstraint(),
                        "pendingClarifyField", workingState.pendingClarifyField()),
                clarifyDecisionPayload(missingField));
        if (missingField != null) {
            return completeAsk(sessionId, traceId, workingState,
                    missingField, clarifyRuleService.questionFor(missingField));
        }
        return completeRecommendation(sessionId, userId, userInput, traceId,
                workingState.withPendingClarifyField(null).withPhase(SessionPhase.RECOMMEND),
                List.of(), null, publicFallbackUsed, fallbackParsingUsed);
    }

    private ClarifyField firstMissingRequiredField(Intent intent, SessionState state) {
        List<ClarifyField> missing = clarifyRuleService.missingRequiredFields(
                intent, state.slots(), state.timeConstraint());
        return missing.isEmpty() ? null : missing.getFirst();
    }

    private Map<String, Object> clarifyDecisionPayload(ClarifyField field) {
        return traceMap("action", field == null ? "READY" : "ASK",
                "questionToAsk", field == null ? null : clarifyRuleService.questionFor(field),
                "missingSlots", field == null ? List.of() : List.of(field.key()));
    }

    private ChatResponse completeAsk(String sessionId, String traceId, SessionState workingState,
                                     ClarifyField field, String question) {
        SessionState clarifyState = workingState.withPhase(SessionPhase.CLARIFY)
                .withPendingClarifyField(field)
                .withPendingRelaxationContext(null);
        sessionStateService.save(clarifyState);
        String businessIntent = clarifyState.currentIntent() == null ? null : clarifyState.currentIntent().name();
        sessionService.appendMessage(sessionId, "assistant", question, businessIntent, traceId);
        ChatResponse response = withConversationContext(
                ChatResponse.clarify(sessionId, traceId, question, List.of(field.key())), clarifyState);
        agentTraceService.recordEvent("RESPONSE_READY", "CLARIFY",
                traceMap("field", field.key(), "question", question, "businessIntent", businessIntent), response);
        return response;
    }

    private ChatResponse handleAdjust(String sessionId, Long userId, String userInput, String traceId,
                                      SessionState state, IntentResult intent, boolean publicFallbackUsed,
                                      boolean fallbackParsingUsed) {
        SlotMutation mutation = applySlotMutation(state, intent);
        SlotBundle mergedSlots = mutation.included();
        SessionState workingState = state.withIntent(Intent.MEAL_ADJUST)
                .withSlots(mergedSlots)
                .withExcludedSlots(mutation.excluded())
                .withUnconstrainedSlots(mutation.unconstrained());
        ClarifyField missingField = firstMissingRequiredField(Intent.MEAL_RECOMMENDATION, workingState);
        if (missingField != null) {
            Map<String, Object> decision = clarifyDecisionPayload(missingField);
            agentTraceService.recordEvent("ADJUST_CLARIFY_DECISION", "CLARIFY",
                    traceMap("slots", mergedSlots, "timeConstraint", workingState.timeConstraint()), decision);
            agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY",
                    traceMap("slots", mergedSlots, "timeConstraint", workingState.timeConstraint()), decision);
            return completeAsk(sessionId, traceId, workingState,
                    missingField, clarifyRuleService.questionFor(missingField));
        }
        workingState = workingState.withPendingClarifyField(null).withPhase(SessionPhase.RECOMMEND);
        String currentQueryKey = recommendationQueryKey(workingState);
        boolean queryChanged = !currentQueryKey.equals(state.recommendationQueryKey());
        List<Long> excludeActivityIds = queryChanged || state.lastRecommendedActivityIds() == null
                ? List.of() : state.lastRecommendedActivityIds();
        agentTraceService.recordEvent("ADJUST_CONTEXT_RESOLVED", "ADJUST", intent,
                traceMap("mergedSlots", mergedSlots,
                        "unconstrainedSlots", workingState.unconstrainedSlots(),
                        "excludeActivityIds", excludeActivityIds,
                        "queryChanged", queryChanged));
        return completeRecommendation(sessionId, userId, userInput, traceId, workingState,
                excludeActivityIds, null, publicFallbackUsed, fallbackParsingUsed);
    }

    private ChatResponse handlePlan(String sessionId, Long userId, String userInput, String traceId,
                                    SessionState state, IntentResult intent, boolean publicFallbackUsed,
                                    boolean fallbackParsingUsed) {
        SlotMutation mutation = applySlotMutation(state, intent);
        SlotBundle mergedSlots = mutation.included();
        SessionState planContextState = state.withIntent(Intent.ACTIVITY_PLAN)
                .withSlots(mergedSlots)
                .withExcludedSlots(mutation.excluded())
                .withUnconstrainedSlots(mutation.unconstrained());
        ClarifyField missingField = firstMissingRequiredField(Intent.ACTIVITY_PLAN, planContextState);
        Map<String, Object> decision = clarifyDecisionPayload(missingField);
        Map<String, Object> decisionInput = traceMap("slots", mergedSlots,
                "timeConstraint", planContextState.timeConstraint(),
                "pendingClarifyField", planContextState.pendingClarifyField());
        agentTraceService.recordEvent("PLAN_CLARIFY_DECISION", "CLARIFY", decisionInput, decision);
        agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY", decisionInput, decision);
        if (missingField != null) {
            return completeAsk(sessionId, traceId, planContextState,
                    missingField, clarifyRuleService.questionFor(missingField));
        }
        List<String> planActivityTimes = activityPlanService.resolveActivityTimes(mergedSlots, planContextState.timeConstraint());
        SessionState workingState = planContextState.withSlots(new SlotBundle(
                        mergedSlots.city(), mergedSlots.location(), mergedSlots.experienceGoal(), mergedSlots.companion(),
                        mergedSlots.budget(), mergedSlots.activityType(), mergedSlots.style(), mergedSlots.duration(), mergedSlots.feature()))
                .withPendingClarifyField(null)
                .withPhase(SessionPhase.PLAN);
        agentTraceService.recordEvent("PLAN_CONTEXT_RESOLVED", "PLAN", intent,
                traceMap("mergedSlots", mergedSlots,
                        "excludedSlots", planContextState.excludedSlots(),
                        "unconstrainedSlots", planContextState.unconstrainedSlots(),
                        "planActivityTimes", planActivityTimes,
                        "planSlots", workingState.slots()));
        return completePlan(sessionId, userId, userInput, traceId, workingState,
                planActivityTimes, publicFallbackUsed, fallbackParsingUsed);
    }

    private ChatResponse completePlan(String sessionId, Long userId, String userInput, String traceId,
                                      SessionState state, List<String> planActivityTimes,
                                      boolean publicFallbackUsed, boolean fallbackParsingUsed) {
        WeatherRecommendationContext weather = weatherRecommendationService.resolve(state.slots(), state.timeConstraint());
        agentTraceService.recordEvent("PLAN_WEATHER_CONTEXT_RESOLVED", "RANK",
                traceMap("slots", state.slots(), "timeConstraint", state.timeConstraint()), weather);
        List<ActivityPlanService.PlannedActivity> plannedMeals = activityPlanService.planActivities(
                state.sourceMode(), userId, state.slots(), state.excludedSlots(),
                planActivityTimes, state.timeConstraint(), weather);
        List<Map<String, Object>> planTrace = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity planned : plannedMeals) {
            planTrace.add(traceMap("period", planned.period(),
                    "matched", planned.matched(),
                    "activityId", planned.matched() ? planned.activity().id() : null,
                    "mealName", planned.matched() ? planned.activity().name() : null,
                    "matchScore", planned.matched() ? planned.activity().matchScore() : null));
        }
        agentTraceService.recordEvent("ACTIVITY_PLAN_SEARCHED", "PLAN",
                traceMap("planActivityTimes", planActivityTimes,
                        "slots", state.slots(),
                        "excludedSlots", state.excludedSlots(),
                        "weather", weather),
                Map.of("plannedCount", plannedMeals.size(), "plannedMeals", planTrace));
        boolean anyMatched = plannedMeals.stream().anyMatch(ActivityPlanService.PlannedActivity::matched);
        if (!anyMatched) {
            ResponseResult empty = ResponseResult.textOnly(state.sourceMode() == SourceMode.PERSONAL
                    ? "你的个人活动库里暂时拼不出多时段方案，可以补充更多常去的地方，或者我帮你看看公共推荐吧。"
                    : "暂时拼不出完整的多时段方案，你可以补充氛围、活动类型，或试试个人模式。");
            if (fallbackParsingUsed) empty = prependFallbackParsingNotice(empty, state);
            agentTraceService.recordEvent("NO_ACTIVITY_PLAN_MATCHED", "PLAN", state, empty);
            return completeTextOnly(sessionId, traceId, state, Intent.ACTIVITY_PLAN, empty);
        }
        RecommendResponseAgentService.Result merged = planResponseAgentService.planAndRespond(
                sessionId, userInput, state.sourceMode(), state.slots(), plannedMeals, weather);
        RecommendResult recommend = merged.recommend();
        agentTraceService.recordEvent("PLAN_RESULT_BUILT", "PLAN",
                Map.of("strategy", Intent.ACTIVITY_PLAN.name(), "plannedMeals", planTrace), recommend);
        ResponseResult response = merged.response();
        if (weather.active()) {
            response = new ResponseResult(weather.summary() + "\n" + response.speechText(),
                    response.displayBlocks(), response.nextAction());
        }
        if (publicFallbackUsed) {
            response = prependPublicFallbackNotice(response);
            agentTraceService.recordEvent("PUBLIC_FALLBACK_NOTICE_APPLIED", "RESPONSE",
                    Map.of("sourceMode", SourceMode.PUBLIC), response);
        }
        if (fallbackParsingUsed) {
            response = prependFallbackParsingNotice(response, state);
            agentTraceService.recordEvent("FALLBACK_PARSE_NOTICE_APPLIED", "RESPONSE",
                    fallbackNoticeTraceInput(state), response);
        }
        agentTraceService.recordEvent("PLAN_RESPONSE_AGENT_RESULT", "RESPONSE", recommend, response);
        response = applyOutputRiskGuard(userInput, Intent.ACTIVITY_PLAN, response);
        List<Long> lastIds = recommend.recommendations().stream().map(option -> option.itemId()).toList();
        String queryKey = recommendationQueryKey(state);
        SessionState savedState = queryKey.equals(state.recommendationQueryKey())
                ? state.appendLastRecommendations(lastIds) : state.withLastRecommendations(lastIds);
        savedState = savedState.withRecommendationQueryKey(queryKey)
                .withPendingClarifyField(null)
                .withPendingRelaxationContext(null);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), Intent.ACTIVITY_PLAN.name(), traceId);
        ChatResponse chatResponse = withConversationContext(ChatResponse.answer(
                sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction()), savedState);
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);
        return chatResponse;
    }

    static String recommendationQueryKey(SessionState state) {
        if (state == null) return "";
        List<String> unconstrained = state.unconstrainedSlots() == null
                ? List.of() : state.unconstrainedSlots().stream().sorted().toList();
        TimeConstraint time = state.timeConstraint();
        String timeKey = time == null ? "null|null|null|null"
                : String.valueOf(time.dateStart()) + "|" + time.dateEnd()
                + "|" + time.startTime() + "|" + time.endTime();
        return String.valueOf(state.sourceMode()) + "|" + state.slots() + "|" + state.excludedSlots()
                + "|" + unconstrained + "|" + timeKey;
    }

    private ChatResponse handleChitchat(String sessionId, String traceId, SessionState state) {
        ResponseResult response = ResponseResult.textOnly(CHITCHAT_REPLY);
        if (hasPendingClarification(state)) {
            return completeStatePreservingChitchat(sessionId, traceId, state, response);
        }
        return completeTextOnly(sessionId, traceId, state, Intent.OTHER, response);
    }

    private boolean hasPendingClarification(SessionState state) {
        return state != null
                && state.phase() == SessionPhase.CLARIFY
                && state.pendingClarifyField() != null
                && state.currentIntent() != null;
    }

    private ChatResponse completeStatePreservingChitchat(String sessionId, String traceId,
                                                          SessionState state, ResponseResult response) {
        sessionStateService.save(state);
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), Intent.OTHER.name(), traceId);
        ChatResponse chatResponse = withConversationContext(
                ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction()), state);
        agentTraceService.recordEvent("RESPONSE_READY", "CHITCHAT",
                traceMap("responseIntent", Intent.OTHER,
                        "businessIntent", state.currentIntent(),
                        "pendingClarifyField", state.pendingClarifyField(),
                        "statePreserved", true), chatResponse);
        return chatResponse;
    }

    private ChatResponse completeRecommendation(String sessionId, Long userId, String userInput, String traceId,
                                                SessionState state, List<Long> excludeActivityIds) {
        return completeRecommendation(sessionId, userId, userInput, traceId,
                state, excludeActivityIds, null, false, false);
    }

    private ChatResponse completeRecommendation(String sessionId, Long userId, String userInput, String traceId,
                                                 SessionState state, List<Long> excludeActivityIds,
                                                 RelaxationSearchService.SearchResult selectedRelaxation) {
        return completeRecommendation(sessionId, userId, userInput, traceId,
                state, excludeActivityIds, selectedRelaxation, false, false);
    }

    private ChatResponse completeRecommendation(String sessionId, Long userId, String userInput, String traceId,
                                                 SessionState state, List<Long> excludeActivityIds,
                                                 RelaxationSearchService.SearchResult selectedRelaxation,
                                                 boolean publicFallbackUsed, boolean fallbackParsingUsed) {
        WeatherRecommendationContext weather = weatherRecommendationService.resolve(state.slots(), state.timeConstraint());
        agentTraceService.recordEvent("WEATHER_CONTEXT_RESOLVED", "RANK",
                traceMap("slots", state.slots(), "timeConstraint", state.timeConstraint()), weather);
        ActivitySearchRequest searchRequest = new ActivitySearchRequest(
                state.sourceMode(), userId, state.slots(), excludeActivityIds,
                state.timeConstraint(), state.excludedSlots());
        List<ActivityItem> candidates = selectedRelaxation == null
                ? activitySearchService.search(searchRequest) : selectedRelaxation.ranked();
        Object searchTraceInput = selectedRelaxation == null ? searchRequest
                : traceMap("selectedRelaxationLevel", selectedRelaxation.level(),
                        "querySlots", selectedRelaxation.querySlots(),
                        "excludedSlots", state.excludedSlots(),
                        "sourceMode", state.sourceMode(),
                        "excludeActivityIds", excludeActivityIds,
                        "timeConstraint", state.timeConstraint());
        agentTraceService.recordEvent("ACTIVITY_SEARCHED", "SEARCH", searchTraceInput,
                Map.of("candidateCount", candidates.size(), "candidates", candidates));
        ActivityRankRequest rankRequest = new ActivityRankRequest(
                candidates, state.slots(), state.timeConstraint(), excludeActivityIds);
        ActivityDiversityResult rankedResult = rankAndDiversify(
                "ACTIVITY_RANKED", "ACTIVITY_DIVERSIFIED", rankRequest, weather);
        List<ActivityItem> ranked = rankedResult.ranked();
        if (ranked.isEmpty()) {
            if (state.sourceMode() == SourceMode.PERSONAL) {
                agentTraceService.recordEvent("PERSONAL_NO_MATCH_FALLBACK", "RECOMMEND", state,
                        Map.of("fallbackSourceMode", SourceMode.PUBLIC));
                ActivitySearchRequest publicSearchRequest = new ActivitySearchRequest(
                        SourceMode.PUBLIC, userId, state.slots(), excludeActivityIds,
                        state.timeConstraint(), state.excludedSlots());
                List<ActivityItem> publicCandidates = activitySearchService.search(publicSearchRequest);
                agentTraceService.recordEvent("ACTIVITY_SEARCHED_PUBLIC_FALLBACK", "SEARCH", publicSearchRequest,
                        Map.of("candidateCount", publicCandidates.size(), "candidates", publicCandidates));
                ActivityRankRequest publicRankRequest = new ActivityRankRequest(
                        publicCandidates, state.slots(), state.timeConstraint(), excludeActivityIds);
                ActivityDiversityResult publicRankedResult = rankAndDiversify(
                        "ACTIVITY_RANKED_PUBLIC_FALLBACK", "ACTIVITY_DIVERSIFIED_PUBLIC_FALLBACK",
                        publicRankRequest, weather);
                List<ActivityItem> publicRanked = publicRankedResult.ranked();
                if (!publicRanked.isEmpty()) {
                    ranked = publicRanked;
                    state = state.withSourceMode(SourceMode.PUBLIC);
                    publicFallbackUsed = true;
                    agentTraceService.recordEvent("FALLBACK_SUCCESS", "RECOMMEND",
                            Map.of("fallbackSourceMode", SourceMode.PUBLIC, "rankedCount", ranked.size()), null);
                } else {
                    ResponseResult empty = ResponseResult.textOnly(
                            "你的个人活动库和公共推荐里都暂时没有很匹配的，可以试试调整时间、预算或氛围描述。");
                    if (fallbackParsingUsed) empty = prependFallbackParsingNotice(empty, state);
                    agentTraceService.recordEvent("NO_ACTIVITY_MATCHED_BOTH", "RECOMMEND", state, empty);
                    return completeTextOnly(sessionId, traceId, state, state.currentIntent(), empty);
                }
            } else {
                List<RelaxationOption> options = relaxationSearchService.options(
                        state.sourceMode(), userId, state.slots(), state.excludedSlots(),
                        excludeActivityIds, state.timeConstraint());
                if (!options.isEmpty()) {
                    String message = "没有完全匹配的活动。你可以选择查看放宽部分偏好后的相近活动，或保持当前严格条件。";
                    if (fallbackParsingUsed) {
                        message = fallbackParsingNotice(state) + "\n" + message;
                        agentTraceService.recordEvent("FALLBACK_PARSE_NOTICE_APPLIED", "RESPONSE",
                                fallbackNoticeTraceInput(state), Map.of("responseType", "RELAX_OPTIONS"));
                    }
                    RelaxationContext relaxationContext = new RelaxationContext(
                            state.sourceMode(), recommendationQueryKey(state), excludeActivityIds,
                            options.stream().map(RelaxationOption::level).toList());
                    agentTraceService.recordEvent("RELAXATION_OPTIONS_READY", "RECOMMEND", state.slots(),
                            traceMap("options", options, "context", relaxationContext));
                    return completeRelaxationChoice(
                            sessionId, traceId, state, message, options, relaxationContext);
                }
                ResponseResult empty = ResponseResult.textOnly(
                        "当前城市暂时没有符合核心条件的活动。你可以切换城市，或调整时间和活动类型后再试。");
                if (fallbackParsingUsed) empty = prependFallbackParsingNotice(empty, state);
                agentTraceService.recordEvent("NO_ACTIVITY_MATCHED", "RECOMMEND", state, empty);
                return completeTextOnly(sessionId, traceId, state, state.currentIntent(), empty);
            }
        }
        RecommendResponseAgentService.Result merged = recommendResponseAgentService.recommendAndRespond(
                sessionId, userInput, state.sourceMode(), state.slots(), ranked, weather);
        RecommendResult recommend = merged.recommend();
        String strategy = state.currentIntent() == null ? Intent.MEAL_RECOMMENDATION.name() : state.currentIntent().name();
        agentTraceService.recordEvent("RECOMMEND_RESULT_BUILT", "RECOMMEND",
                Map.of("strategy", strategy, "ranked", ranked), recommend);
        ResponseResult response = merged.response();
        if (weather.active()) {
            response = new ResponseResult(weather.summary() + "\n" + response.speechText(),
                    response.displayBlocks(), response.nextAction());
        }
        if (selectedRelaxation != null) {
            response = new ResponseResult(
                    "没有完全匹配的活动，已" + selectedRelaxation.label()
                            + "。以下结果按原始需求的接近程度排序。\n" + response.speechText(),
                    response.displayBlocks(), response.nextAction());
        }
        if (publicFallbackUsed) {
            response = prependPublicFallbackNotice(response);
            agentTraceService.recordEvent("PUBLIC_FALLBACK_NOTICE_APPLIED", "RESPONSE",
                    Map.of("sourceMode", SourceMode.PUBLIC), response);
        }
        if (fallbackParsingUsed) {
            response = prependFallbackParsingNotice(response, state);
            agentTraceService.recordEvent("FALLBACK_PARSE_NOTICE_APPLIED", "RESPONSE",
                    fallbackNoticeTraceInput(state), response);
        }
        agentTraceService.recordEvent("RESPONSE_AGENT_RESULT", "RESPONSE", recommend, response);
        response = applyOutputRiskGuard(userInput, state.currentIntent(), response);
        List<Long> lastIds = recommend.recommendations().stream().map(option -> option.itemId()).toList();
        String queryKey = recommendationQueryKey(state);
        SessionState savedState = queryKey.equals(state.recommendationQueryKey())
                ? state.appendLastRecommendations(lastIds) : state.withLastRecommendations(lastIds);
        savedState = savedState.withRecommendationQueryKey(queryKey)
                .withPendingClarifyField(null)
                .withPendingRelaxationContext(null);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), state.currentIntent().name(), traceId);
        ChatResponse chatResponse = withConversationContext(
                ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction()), savedState);
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);
        return chatResponse;
    }

    private ResponseResult applyOutputRiskGuard(String userInput, Intent intent, ResponseResult response) {
        RiskGuardResult guard = riskGuardService.check(userInput, response);
        agentTraceService.recordEvent("RISK_GUARD_OUTPUT_CHECKED", "GUARD",
                traceMap("intent", intent, "response", response), guard);
        if (!guard.passed()) {
            ResponseResult rewritten = ResponseResult.textOnly(guard.rewriteSuggestion());
            agentTraceService.recordEvent("RISK_GUARD_OUTPUT_REWRITTEN", "GUARD", guard, rewritten);
            return rewritten;
        }
        agentTraceService.recordEvent("RISK_GUARD_OUTPUT_PASSED", "GUARD", null, response);
        return response;
    }

    private ActivityDiversityResult rankAndDiversify(String rankEvent, String diversityEvent,
                                                     ActivityRankRequest rankRequest,
                                                     WeatherRecommendationContext weather) {
        ActivityRankResult rankResult = activityRankService.rank(rankRequest, weather);
        agentTraceService.recordEvent(rankEvent, "RANK", rankRequest,
                traceMap("rankedCount", rankResult.ranked().size(),
                        "scores", rankResult.scores(), "ranked", rankResult.ranked()));
        ActivityDiversityResult diversityResult = activityDiversityService.rerank(rankResult.ranked());
        agentTraceService.recordEvent(diversityEvent, "RANK",
                traceMap("nearTieThreshold", 0.03, "relevanceRanked", rankResult.ranked()),
                traceMap("rankedCount", diversityResult.ranked().size(),
                        "decisions", diversityResult.decisions(), "ranked", diversityResult.ranked()));
        return diversityResult;
    }

    static ResponseResult prependPublicFallbackNotice(ResponseResult response) {
        return response;
    }

    static ResponseResult prependFallbackParsingNotice(ResponseResult response, SessionState state) {
        if (response == null) return null;
        String notice = fallbackParsingNotice(state);
        if (notice.isBlank()) return response;
        String body = response.speechText() == null || response.speechText().isBlank()
                ? notice : notice + "\n" + response.speechText();
        return new ResponseResult(body, response.displayBlocks(), response.nextAction());
    }

    static String fallbackParsingNotice(SessionState state) {
        if (state == null) return "";
        List<String> conditions = new ArrayList<>();
        conditions.add("数据源=" + (state.sourceMode() == SourceMode.PERSONAL ? "个人活动库" : "公共活动库"));
        SlotBundle slots = state.slots() == null ? SlotBundle.empty() : state.slots();
        appendCondition(conditions, "城市", slots.city());
        appendCondition(conditions, "区域", slots.location());
        appendCondition(conditions, "体验目标", slots.experienceGoal());
        appendCondition(conditions, "同行", slots.companion());
        appendCondition(conditions, "预算", slots.budget());
        appendCondition(conditions, "活动类型", slots.activityType());
        appendCondition(conditions, "风格", slots.style());
        appendCondition(conditions, "活动时长", slots.duration());
        appendCondition(conditions, "特征", slots.feature());

        SlotBundle excluded = state.excludedSlots() == null ? SlotBundle.empty() : state.excludedSlots();
        appendExcludedCondition(conditions, "城市", excluded.city());
        appendExcludedCondition(conditions, "区域", excluded.location());
        appendExcludedCondition(conditions, "体验目标", excluded.experienceGoal());
        appendExcludedCondition(conditions, "同行", excluded.companion());
        appendExcludedCondition(conditions, "预算", excluded.budget());
        appendExcludedCondition(conditions, "活动类型", excluded.activityType());
        appendExcludedCondition(conditions, "风格", excluded.style());
        appendExcludedCondition(conditions, "活动时长", excluded.duration());
        appendExcludedCondition(conditions, "特征", excluded.feature());

        if (state.unconstrainedSlots() != null) {
            state.unconstrainedSlots().stream().sorted()
                    .forEach(field -> conditions.add(slotLabel(field) + "=不限"));
        }
        TimeConstraint time = state.timeConstraint();
        if (time != null && time.hasDate()) {
            String date = time.dateStart().equals(time.dateEnd())
                    ? time.dateStart().toString()
                    : time.dateStart() + "至" + time.dateEnd();
            conditions.add("日期=" + date);
        }
        if (time != null && time.hasTime()) {
            conditions.add("时段=" + time.startTime() + "-" + time.endTime());
        }
        return "这轮我采用了保守解析，实际按以下条件进行召回："
                + String.join("；", conditions)
                + "。如果有遗漏或理解不准，可以继续补充或修改，我会按新条件重新搜索。";
    }

    private static void appendCondition(List<String> conditions, String label, List<String> values) {
        if (values != null && !values.isEmpty()) conditions.add(label + "=" + String.join("、", values));
    }

    private static void appendExcludedCondition(List<String> conditions, String label, List<String> values) {
        if (values != null && !values.isEmpty()) conditions.add("排除" + label + "=" + String.join("、", values));
    }

    private static String slotLabel(String field) {
        return switch (field) {
            case "city" -> "城市";
            case "location" -> "区域";
            case "experienceGoal" -> "体验目标";
            case "companion" -> "同行";
            case "budget" -> "预算";
            case "activityType" -> "活动类型";
            case "style" -> "风格";
            case "duration" -> "活动时长";
            case "feature" -> "特征";
            default -> field;
        };
    }

    private Map<String, Object> fallbackNoticeTraceInput(SessionState state) {
        return traceMap("sourceMode", state.sourceMode(),
                "slots", state.slots(),
                "excludedSlots", state.excludedSlots(),
                "unconstrainedSlots", state.unconstrainedSlots(),
                "timeConstraint", state.timeConstraint());
    }

    private ChatResponse completeRelaxationChoice(String sessionId, String traceId, SessionState state,
                                                   String message, List<RelaxationOption> options,
                                                   RelaxationContext relaxationContext) {
        SessionState savedState = state.withIntent(Intent.MEAL_RECOMMENDATION)
                .withPendingClarifyField(null)
                .withPendingRelaxationContext(relaxationContext);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", message, Intent.MEAL_RECOMMENDATION.name(), traceId);
        ChatResponse response = withConversationContext(
                ChatResponse.relaxation(sessionId, traceId, message, options), savedState);
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, response);
        return response;
    }

    private ChatResponse withConversationContext(ChatResponse response, SessionState state) {
        if (response == null || state == null) return response;
        response.appliedSlots(state.slots());
        response.excludedSlots(state.excludedSlots());
        response.timeConstraint(state.timeConstraint());
        return response;
    }

    private ChatResponse completeGuardedTextOnly(String sessionId, String traceId,
                                                 SessionState state, ResponseResult response) {
        sessionStateService.save(state);
        String businessIntent = state.currentIntent() == null ? null : state.currentIntent().name();
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), businessIntent, traceId);
        ChatResponse chatResponse = withConversationContext(
                ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction()), state);
        agentTraceService.recordEvent("RESPONSE_READY", "GUARD",
                traceMap("businessIntent", businessIntent, "statePreserved", true), chatResponse);
        return chatResponse;
    }

    private ChatResponse completeTextOnly(String sessionId, String traceId, SessionState state,
                                          Intent intent, ResponseResult response) {
        SessionState savedState = state.withIntent(intent)
                .withPendingClarifyField(null)
                .withPendingRelaxationContext(null);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), intent.name(), traceId);
        ChatResponse chatResponse = ChatResponse.answer(
                sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction());
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE",
                Map.of("intent", intent, "state", savedState), chatResponse);
        return chatResponse;
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private Map<String, Object> traceMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }
}

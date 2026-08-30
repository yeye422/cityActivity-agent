package com.city.service.orchestrator;

import com.city.enums.ClarifyAction;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivitySearchRequest;
import com.city.model.ChatRequest;
import com.city.model.ChatResponse;
import com.city.model.ClarifyResult;
import com.city.model.IntentResult;
import com.city.model.RecommendResult;
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
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import com.city.service.activity.ActivityService;
import com.city.service.activity.RelaxationSearchService;
import com.city.service.clarify.ClarifyAgentService;
import com.city.service.intent.IntentAgentService;
import com.city.service.intent.IntentReviseService;
import com.city.service.plan.ActivityPlanService;
import com.city.service.plan.PlanResponseAgentService;
import com.city.service.recommend.RecommendResponseAgentService;
import com.city.service.risk.RiskGuardService;
import com.city.service.session.SessionService;
import com.city.service.session.SessionStateService;
import com.city.service.slot.SlotMergeService;
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
    private static final String TIME_CLARIFY_QUESTION = "我没能准确理解你的时间要求。可以说得更具体一点吗？例如「下周六下午3点」或「晚上7点到9点」。";

    private final SessionService sessionService;
    private final SessionStateService sessionStateService;
    private final IntentAgentService intentAgentService;
    private final IntentReviseService intentReviseService;
    private final SlotMergeService slotMergeService;
    private final SlotOptionService slotOptionService;
    private final SlotMutationService slotMutationService;
    private final ClarifyAgentService clarifyAgentService;
    private final ActivitySearchService activitySearchService;
    private final ActivityRankService activityRankService;
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
            SlotMergeService slotMergeService,
            SlotOptionService slotOptionService,
            SlotMutationService slotMutationService,
            ClarifyAgentService clarifyAgentService,
            ActivitySearchService activitySearchService,
            ActivityRankService activityRankService,
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
        this.slotMergeService = slotMergeService;
        this.slotOptionService = slotOptionService;
        this.slotMutationService = slotMutationService;
        this.clarifyAgentService = clarifyAgentService;
        this.activitySearchService = activitySearchService;
        this.activityRankService = activityRankService;
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
        if (request == null || request.sessionId() == null || request.sessionId().isBlank() || request.sourceMode() == null) {
            throw new CityException("相近活动请求参数不完整");
        }
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        SessionState state = sessionStateService.loadOrCreate(request.sessionId(), userId, request.sourceMode());
        try (AgentTraceService.TraceScope ignored = agentTraceService.openTrace(traceId, state.sessionId(), userId)) {
            RelaxationSearchService.SearchResult result = relaxationSearchService.find(
                    state.sourceMode(), userId, state.slots(), List.of(), state.timeConstraint(), request.level());
            agentTraceService.recordEvent("RELAXATION_SELECTED", "SEARCH", request,
                    traceMap("level", result.level(), "relaxedSlots", result.relaxedSlots(), "querySlots", result.querySlots(), "candidateCount", result.ranked().size()));
            if (result.ranked().isEmpty()) {
                throw new CityException("该相近活动方案暂时没有结果，请调整条件");
            }
            return completeRecommendation(state.sessionId(), userId, "查看相近活动：" + result.label(), traceId, state, List.of(), result);
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

        if (sourceMode == SourceMode.PERSONAL && !activityService.hasPersonalActivities(userId)) {
            agentTraceService.recordEvent("PERSONAL_LIBRARY_EMPTY_FALLBACK_TO_PUBLIC", "ROUTE",
                    Map.of("userId", userId, "originalSourceMode", sourceMode),
                    Map.of("fallbackSourceMode", SourceMode.PUBLIC));
            state = state.withSourceMode(SourceMode.PUBLIC);
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
        TimeResolutionResult timeResolution = timeResolutionService.resolve(
                state.timeConstraint(), intent.temporal(), request.message());
        agentTraceService.recordEvent("TIME_RESOLUTION_DECIDED", "TIME", intent.temporal(), timeResolution);

        if (timeResolution.needsClarification() && isActivityFlow(intent.intent())) {
            agentTraceService.recordEvent("TIME_PARSE_CLARIFY", "TIME", request.message(), timeResolution);
            SessionState clarifyState = state.withIntent(intent.intent());
            return completeAsk(sessionId, traceId, clarifyState,
                    ClarifyResult.ask(TIME_CLARIFY_QUESTION, List.of("time")));
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

        agentTraceService.recordEvent("INTENT_REVISED", "INTENT", rawIntent, intent);
        agentTraceService.recordEvent("ROUTE_SELECTED", "ROUTE", intent, Map.of("route", intent.intent()));

        return switch (intent.intent()) {
            case MEAL_RECOMMENDATION, CLARIFY_NEEDED ->
                    handleRecommendation(sessionId, userId, request.message(), traceId, state, intent);
            case MEAL_ADJUST -> handleAdjust(sessionId, userId, request.message(), traceId, state, intent);
            case ACTIVITY_PLAN -> handlePlan(sessionId, userId, request.message(), traceId, state, intent);
            case HEALTH_RISK -> handleHealthRisk(sessionId, traceId, state);
            case OTHER -> handleChitchat(sessionId, traceId, state);
        };
    }

    private boolean isActivityFlow(Intent intent) {
        return intent == Intent.MEAL_RECOMMENDATION
                || intent == Intent.MEAL_ADJUST
                || intent == Intent.ACTIVITY_PLAN
                || intent == Intent.CLARIFY_NEEDED;
    }

    private SlotBundle contextSlots(Map<String, Object> context) {
        if (context == null || context.isEmpty()) return SlotBundle.empty();
        String city = contextValue(context, "city");
        String location = contextValue(context, "location");
        return slotOptionService.sanitize(new SlotBundle(
                city.isBlank() ? List.of() : List.of(city),
                location.isBlank() ? List.of() : List.of(location),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
    }

    private SessionState applyContextSlots(SessionState state, SlotBundle contextSlots) {
        SlotBundle historical = state.slots() == null ? SlotBundle.empty() : state.slots();
        SlotBundle context = contextSlots == null ? SlotBundle.empty() : contextSlots;
        SlotBundle applied = new SlotBundle(
                context.city().isEmpty() ? historical.city() : context.city(),
                context.location().isEmpty() ? historical.location() : context.location(),
                historical.mood(), historical.scene(), historical.budget(), historical.activityType(),
                historical.style(), historical.duration()
        );
        Set<String> unconstrained = new LinkedHashSet<>(
                state.unconstrainedSlots() == null ? Set.of() : state.unconstrainedSlots());
        if (!context.city().isEmpty()) {
            unconstrained.remove("city");
        }
        if (!context.location().isEmpty()) {
            unconstrained.remove("location");
        }
        return state.withSlots(applied).withUnconstrainedSlots(unconstrained);
    }

    private String contextValue(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private ChatResponse handleRecommendation(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent) {
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());
        SlotMutation mutation = slotMutationService.apply(
                intent.operations(), userInput, mergedSlots, state.excludedSlots(), state.unconstrainedSlots());
        mergedSlots = mutation.included();
        agentTraceService.recordEvent("SLOTS_MERGED", "SLOT",
                traceMap("stateSlots", state.slots(), "intentSlots", intent.slots()),
                traceMap("mergedSlots", mergedSlots, "unconstrainedSlots", mutation.unconstrained()));

        SessionState workingState = state.withIntent(Intent.MEAL_RECOMMENDATION)
                .withSlots(mergedSlots)
                .withExcludedSlots(mutation.excluded())
                .withUnconstrainedSlots(mutation.unconstrained());
        ClarifyResult clarify = clarifyAgentService.decide(
                sessionId, userInput, mergedSlots, workingState.timeConstraint(), workingState.unconstrainedSlots());
        agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY",
                traceMap("slots", mergedSlots, "unconstrainedSlots", workingState.unconstrainedSlots()), clarify);
        if (clarify.action() == ClarifyAction.ASK) {
            return completeAsk(sessionId, traceId, workingState, clarify);
        }
        return completeRecommendation(sessionId, userId, userInput, traceId,
                workingState.withPhase(SessionPhase.RECOMMEND), List.of());
    }

    private ChatResponse completeAsk(String sessionId, String traceId, SessionState workingState, ClarifyResult clarify) {
        SessionState clarifyState = workingState.withPhase(SessionPhase.CLARIFY);
        sessionStateService.save(clarifyState);
        sessionService.appendMessage(sessionId, "assistant", clarify.questionToAsk(), Intent.CLARIFY_NEEDED.name(), traceId);
        ChatResponse response = withConversationContext(
                ChatResponse.clarify(sessionId, traceId, clarify.questionToAsk(), clarify.missingSlots()), clarifyState);
        agentTraceService.recordEvent("RESPONSE_READY", "CLARIFY", clarify, response);
        return response;
    }

    private ChatResponse handleAdjust(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent) {
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());
        SlotMutation mutation = slotMutationService.apply(
                intent.operations(), userInput, mergedSlots, state.excludedSlots(), state.unconstrainedSlots());
        mergedSlots = mutation.included();
        SessionState workingState = state.withIntent(Intent.MEAL_ADJUST)
                .withSlots(mergedSlots)
                .withExcludedSlots(mutation.excluded())
                .withUnconstrainedSlots(mutation.unconstrained())
                .withPhase(SessionPhase.RECOMMEND);

        String currentQueryKey = recommendationQueryKey(workingState);
        boolean queryChanged = !currentQueryKey.equals(state.recommendationQueryKey());
        List<Long> excludeActivityIds = queryChanged || state.lastRecommendedActivityIds() == null
                ? List.of()
                : state.lastRecommendedActivityIds();
        agentTraceService.recordEvent("ADJUST_CONTEXT_RESOLVED", "ADJUST", intent,
                traceMap("mergedSlots", mergedSlots,
                        "unconstrainedSlots", workingState.unconstrainedSlots(),
                        "excludeActivityIds", excludeActivityIds,
                        "queryChanged", queryChanged));
        return completeRecommendation(sessionId, userId, userInput, traceId, workingState, excludeActivityIds);
    }

    private ChatResponse handlePlan(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent) {
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());
        SlotMutation mutation = slotMutationService.apply(
                intent.operations(), userInput, mergedSlots, state.excludedSlots(), state.unconstrainedSlots());
        mergedSlots = mutation.included();
        SessionState planContextState = state.withIntent(Intent.ACTIVITY_PLAN)
                .withSlots(mergedSlots)
                .withExcludedSlots(mutation.excluded())
                .withUnconstrainedSlots(mutation.unconstrained());
        ClarifyResult clarify = clarifyAgentService.decide(
                sessionId, userInput, mergedSlots, planContextState.timeConstraint(), planContextState.unconstrainedSlots());
        agentTraceService.recordEvent("PLAN_CLARIFY_DECISION", "CLARIFY",
                traceMap("slots", mergedSlots, "unconstrainedSlots", planContextState.unconstrainedSlots()), clarify);
        if (clarify.action() == ClarifyAction.ASK) {
            return completeAsk(sessionId, traceId, planContextState, clarify);
        }

        List<String> planActivityTimes = activityPlanService.resolveActivityTimes(mergedSlots, planContextState.timeConstraint());
        SlotBundle planSlots = new SlotBundle(
                mergedSlots.city(), mergedSlots.location(), mergedSlots.mood(), mergedSlots.scene(),
                mergedSlots.budget(), mergedSlots.activityType(), mergedSlots.style(), mergedSlots.duration()
        );
        agentTraceService.recordEvent(
                "PLAN_CONTEXT_RESOLVED", "PLAN", intent,
                traceMap("mergedSlots", mergedSlots,
                        "unconstrainedSlots", planContextState.unconstrainedSlots(),
                        "planActivityTimes", planActivityTimes,
                        "planSlots", planSlots)
        );
        SessionState workingState = planContextState.withSlots(planSlots).withPhase(SessionPhase.PLAN);
        return completePlan(sessionId, userId, userInput, traceId, workingState, planActivityTimes);
    }

    private ChatResponse completePlan(String sessionId,
                                      Long userId,
                                      String userInput,
                                      String traceId,
                                      SessionState state,
                                      List<String> planActivityTimes) {
        List<ActivityPlanService.PlannedActivity> plannedMeals = activityPlanService.planActivities(
                state.sourceMode(), userId, state.slots(), planActivityTimes, state.timeConstraint());

        List<Map<String, Object>> planTrace = new ArrayList<>();
        for (ActivityPlanService.PlannedActivity planned : plannedMeals) {
            planTrace.add(traceMap(
                    "period", planned.period(),
                    "matched", planned.matched(),
                    "activityId", planned.matched() ? planned.activity().id() : null,
                    "mealName", planned.matched() ? planned.activity().name() : null
            ));
        }
        agentTraceService.recordEvent(
                "ACTIVITY_PLAN_SEARCHED", "PLAN",
                Map.of("planActivityTimes", planActivityTimes, "slots", state.slots()),
                Map.of("plannedCount", plannedMeals.size(), "plannedMeals", planTrace)
        );

        boolean anyMatched = plannedMeals.stream().anyMatch(ActivityPlanService.PlannedActivity::matched);
        if (!anyMatched) {
            ResponseResult empty = ResponseResult.textOnly(state.sourceMode() == SourceMode.PERSONAL
                    ? "你的个人活动库里暂时拼不出多时段方案，可以补充更多常去的地方，或者我帮你看看公共推荐吧。"
                    : "暂时拼不出完整的多时段方案，你可以补充氛围、活动类型，或试试个人模式。");
            agentTraceService.recordEvent("NO_ACTIVITY_PLAN_MATCHED", "PLAN", state, empty);
            return completeTextOnly(sessionId, traceId, state, Intent.ACTIVITY_PLAN, empty);
        }

        RecommendResponseAgentService.Result merged = planResponseAgentService.planAndRespond(
                sessionId, userInput, state.sourceMode(), state.slots(), plannedMeals);
        RecommendResult recommend = merged.recommend();
        agentTraceService.recordEvent(
                "PLAN_RESULT_BUILT", "PLAN",
                Map.of("strategy", Intent.ACTIVITY_PLAN.name(), "plannedMeals", planTrace), recommend);

        ResponseResult response = merged.response();
        agentTraceService.recordEvent("PLAN_RESPONSE_AGENT_RESULT", "RESPONSE", recommend, response);
        RiskGuardResult guard = riskGuardService.check(userInput, Intent.ACTIVITY_PLAN, recommend, response);
        agentTraceService.recordEvent(
                "NUTRITION_GUARD_CHECKED", "GUARD",
                Map.of("intent", Intent.ACTIVITY_PLAN, "response", response), guard);

        if (!guard.passed()) {
            response = ResponseResult.textOnly(guard.rewriteSuggestion());
            agentTraceService.recordEvent("NUTRITION_GUARD_REWRITTEN", "GUARD", guard, response);
        } else {
            agentTraceService.recordEvent("COMPLIANCE_GUARD_REWRITTEN", "GUARD", null, response);
        }

        List<Long> lastIds = recommend.recommendations().stream().map(option -> option.itemId()).toList();
        String queryKey = recommendationQueryKey(state);
        SessionState savedState = queryKey.equals(state.recommendationQueryKey())
                ? state.appendLastRecommendations(lastIds)
                : state.withLastRecommendations(lastIds);
        savedState = savedState.withRecommendationQueryKey(queryKey);
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
                ? List.of()
                : state.unconstrainedSlots().stream().sorted().toList();
        TimeConstraint time = state.timeConstraint();
        String timeKey = time == null
                ? "null|null|null|null"
                : String.valueOf(time.dateStart()) + "|" + time.dateEnd()
                + "|" + time.startTime() + "|" + time.endTime();
        return String.valueOf(state.sourceMode()) + "|" + state.slots() + "|" + state.excludedSlots()
                + "|" + unconstrained + "|" + timeKey;
    }

    private ChatResponse handleHealthRisk(String sessionId, String traceId, SessionState state) {
        ResponseResult response = ResponseResult.textOnly(riskGuardService.conservativeMessage());
        return completeTextOnly(sessionId, traceId, state, Intent.HEALTH_RISK, response);
    }

    private ChatResponse handleChitchat(String sessionId, String traceId, SessionState state) {
        ResponseResult response = ResponseResult.textOnly(CHITCHAT_REPLY);
        return completeTextOnly(sessionId, traceId, state, Intent.OTHER, response);
    }

    private ChatResponse completeRecommendation(String sessionId, Long userId, String userInput, String traceId, SessionState state, List<Long> excludeActivityIds) {
        return completeRecommendation(sessionId, userId, userInput, traceId, state, excludeActivityIds, null);
    }

    private ChatResponse completeRecommendation(String sessionId, Long userId, String userInput, String traceId,
                                                 SessionState state, List<Long> excludeActivityIds,
                                                 RelaxationSearchService.SearchResult selectedRelaxation) {
        WeatherRecommendationContext weather = weatherRecommendationService.resolve(userInput, state.slots());
        agentTraceService.recordEvent("WEATHER_CONTEXT_RESOLVED", "RANK", state.slots(), weather);
        List<ActivityItem> candidates = selectedRelaxation == null
                ? activitySearchService.search(new ActivitySearchRequest(state.sourceMode(), userId, state.slots(), excludeActivityIds, state.timeConstraint(), state.excludedSlots()))
                : selectedRelaxation.ranked();
        agentTraceService.recordEvent("ACTIVITY_SEARCHED", "SEARCH", state.slots(), Map.of("candidateCount", candidates.size(), "candidates", candidates));

        List<ActivityItem> ranked = activityRankService.rank(new ActivityRankRequest(candidates, state.slots(), excludeActivityIds), weather);
        agentTraceService.recordEvent("ACTIVITY_RANKED", "RANK", Map.of("excludeActivityIds", excludeActivityIds), Map.of("rankedCount", ranked.size(), "ranked", ranked));

        if (ranked.isEmpty()) {
            if (state.sourceMode() == SourceMode.PERSONAL) {
                agentTraceService.recordEvent("PERSONAL_NO_MATCH_FALLBACK", "RECOMMEND", state,
                        Map.of("fallbackSourceMode", SourceMode.PUBLIC));
                List<ActivityItem> publicCandidates = activitySearchService.search(
                        new ActivitySearchRequest(SourceMode.PUBLIC, userId, state.slots(), excludeActivityIds, state.timeConstraint(), state.excludedSlots()));
                agentTraceService.recordEvent("ACTIVITY_SEARCHED_PUBLIC_FALLBACK", "SEARCH", state.slots(),
                        Map.of("candidateCount", publicCandidates.size(), "candidates", publicCandidates));
                List<ActivityItem> publicRanked = activityRankService.rank(
                        new ActivityRankRequest(publicCandidates, state.slots(), excludeActivityIds), weather);
                agentTraceService.recordEvent("ACTIVITY_RANKED_PUBLIC_FALLBACK", "RANK",
                        Map.of("excludeActivityIds", excludeActivityIds),
                        Map.of("rankedCount", publicRanked.size(), "ranked", publicRanked));
                if (!publicRanked.isEmpty()) {
                    ranked = publicRanked;
                    state = state.withSourceMode(SourceMode.PUBLIC);
                    agentTraceService.recordEvent("FALLBACK_SUCCESS", "RECOMMEND",
                            Map.of("fallbackSourceMode", SourceMode.PUBLIC, "rankedCount", ranked.size()), null);
                } else {
                    ResponseResult empty = ResponseResult.textOnly(
                            "你的个人活动库和公共推荐里都暂时没有很匹配的，可以试试调整时间、预算或氛围描述。");
                    agentTraceService.recordEvent("NO_ACTIVITY_MATCHED_BOTH", "RECOMMEND", state, empty);
                    return completeTextOnly(sessionId, traceId, state, state.currentIntent(), empty);
                }
            } else {
                List<RelaxationOption> options = relaxationSearchService.options(
                        state.sourceMode(), userId, state.slots(), excludeActivityIds, state.timeConstraint());
                if (!options.isEmpty()) {
                    String message = "没有完全匹配的活动。你可以选择查看放宽部分偏好后的相近活动，或保持当前严格条件。";
                    agentTraceService.recordEvent("RELAXATION_OPTIONS_READY", "RECOMMEND", state.slots(), options);
                    return completeRelaxationChoice(sessionId, traceId, state, message, options);
                }
                ResponseResult empty = ResponseResult.textOnly(
                        "当前城市暂时没有符合核心条件的活动。你可以切换城市，或调整时间和活动类型后再试。");
                agentTraceService.recordEvent("NO_ACTIVITY_MATCHED", "RECOMMEND", state, empty);
                return completeTextOnly(sessionId, traceId, state, state.currentIntent(), empty);
            }
        }

        RecommendResponseAgentService.Result merged = recommendResponseAgentService.recommendAndRespond(
                sessionId, userInput, state.sourceMode(), state.slots(), ranked, weather);
        RecommendResult recommend = merged.recommend();
        String strategy = state.currentIntent() == null ? Intent.MEAL_RECOMMENDATION.name() : state.currentIntent().name();
        agentTraceService.recordEvent("RECOMMEND_RESULT_BUILT", "RECOMMEND", Map.of("strategy", strategy, "ranked", ranked), recommend);

        ResponseResult response = merged.response();
        if (weather.active()) {
            response = new ResponseResult(weather.summary() + "\n" + response.speechText(),
                    response.displayBlocks(), response.nextAction());
        }
        if (selectedRelaxation != null) {
            response = new ResponseResult(
                    "没有完全匹配的活动，已" + selectedRelaxation.label() + "。以下结果按原始需求的接近程度排序。\n" + response.speechText(),
                    response.displayBlocks(), response.nextAction());
        }
        agentTraceService.recordEvent("RESPONSE_AGENT_RESULT", "RESPONSE", recommend, response);

        RiskGuardResult guard = riskGuardService.check(userInput, state.currentIntent(), recommend, response);
        agentTraceService.recordEvent("NUTRITION_GUARD_CHECKED", "GUARD", Map.of("intent", state.currentIntent(), "response", response), guard);
        if (!guard.passed()) {
            response = ResponseResult.textOnly(guard.rewriteSuggestion());
            agentTraceService.recordEvent("NUTRITION_GUARD_REWRITTEN", "GUARD", guard, response);
        } else {
            agentTraceService.recordEvent("COMPLIANCE_GUARD_REWRITTEN", "GUARD", null, response);
        }

        List<Long> lastIds = recommend.recommendations().stream().map(option -> option.itemId()).toList();
        String queryKey = recommendationQueryKey(state);
        SessionState savedState = queryKey.equals(state.recommendationQueryKey())
                ? state.appendLastRecommendations(lastIds)
                : state.withLastRecommendations(lastIds);
        savedState = savedState.withRecommendationQueryKey(queryKey);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), state.currentIntent().name(), traceId);
        ChatResponse chatResponse = withConversationContext(
                ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction()), savedState);
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);
        return chatResponse;
    }

    private ChatResponse completeRelaxationChoice(String sessionId, String traceId, SessionState state,
                                                   String message, List<RelaxationOption> options) {
        SessionState savedState = state.withIntent(Intent.MEAL_RECOMMENDATION);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", message, Intent.MEAL_RECOMMENDATION.name(), traceId);
        ChatResponse response = withConversationContext(ChatResponse.relaxation(sessionId, traceId, message, options), savedState);
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

    private ChatResponse completeTextOnly(String sessionId, String traceId, SessionState state, Intent intent, ResponseResult response) {
        SessionState savedState = state.withIntent(intent);
        sessionStateService.save(savedState);
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), intent.name(), traceId);
        ChatResponse chatResponse = ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction());
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", Map.of("intent", intent, "state", savedState), chatResponse);
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

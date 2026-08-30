package com.city.service.orchestrator;

import com.city.exception.CityException;
import com.city.service.intent.IntentAgentService;
import com.city.service.intent.IntentReviseService;
import com.city.service.risk.RiskGuardService;
import com.city.enums.ClarifyAction;
import com.city.model.ClarifyResult;
import com.city.model.RiskGuardResult;
import com.city.enums.Intent;
import com.city.model.IntentResult;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivitySearchRequest;
import com.city.model.ChatRequest;
import com.city.model.ChatResponse;
import com.city.model.RecommendResult;
import com.city.model.ResponseResult;
import com.city.model.RelaxationOption;
import com.city.model.RelaxationRequest;
import com.city.enums.SessionPhase;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.TimeConstraint;
import com.city.enums.SourceMode;
import com.city.service.clarify.ClarifyAgentService;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import com.city.service.activity.ActivityService;
import com.city.service.activity.RelaxationSearchService;
import com.city.service.plan.ActivityPlanService;
import com.city.service.plan.PlanResponseAgentService;
import com.city.service.recommend.RecommendResponseAgentService;
import com.city.service.session.SessionService;
import com.city.service.session.SessionStateService;
import com.city.service.slot.SlotMergeService;
import com.city.service.slot.SlotOptionService;
import com.city.service.slot.SlotMutationService;
import com.city.model.SlotMutation;
import com.city.model.ConstraintOperation;
import com.city.enums.ConstraintOperationType;
import com.city.service.trace.AgentTraceService;
import com.city.service.weather.WeatherRecommendationService;
import com.city.service.time.TimeExpressionParser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 饮食推荐多 Agent 编排服务（Orchestrator）。
 * <p>
 * 一轮 {@link #dietChat} 的主链路：加载会话 → Trace → 加锁 → 记录消息 → 意图识别 → 路由 → 推荐/澄清/固定回复 → 落库。
 */
@Service
public class CityOrchestratorService {

    /**
     * 闲聊时的固定引导文案，不额外调用 LLM。
     */
    private static final String CHITCHAT_REPLY = "我是城市周末活动助手，帮你发现周末好去处。你可以告诉我时间、预算、想要的氛围，比如「周六和朋友，预算200以内，想放松」。";

    /**
     * 会话消息落库服务，写入 diet_messages 表。
     */
    private final SessionService sessionService;

    /**
     * 会话状态服务，读写 phase、slots、lastRecommendedActivityIds 等到 diet_sessions 表。
     */
    private final SessionStateService sessionStateService;

    /**
     * 意图识别 Agent 服务，调用 LLM 识别 intent + slots。
     */
    private final IntentAgentService intentAgentService;

    /**
     * 意图矫正规则服务，用历史状态二次修正 LLM 输出。
     */
    private final IntentReviseService intentReviseService;

    /**
     * 槽位合并服务，多轮对话中合并历史槽位与本轮槽位。
     */
    private final SlotMergeService slotMergeService;
    private final SlotOptionService slotOptionService;
    private final SlotMutationService slotMutationService;

    /**
     * 澄清 Agent 服务，槽位不足时生成追问文案。
     */
    private final ClarifyAgentService clarifyAgentService;

    /**
     * 餐食检索服务，按 sourceMode + slots 从 DB 召回候选。
     */
    private final ActivitySearchService activitySearchService;

    /**
     * 餐食重排服务，对候选按槽位命中二次打分排序。
     */
    private final ActivityRankService activityRankService;

    /**
     * 推荐应答 Agent 服务，一次 LLM 调用生成推荐理由 + 口语回复。
     */
    private final RecommendResponseAgentService recommendResponseAgentService;

    /**
     * 多餐规划服务：解析餐次并按餐次拆分检索重排。
     */
    private final ActivityPlanService activityPlanService;

    /**
     * 多餐规划应答 Agent：按餐次生成理由与口语回复。
     */
    private final PlanResponseAgentService planResponseAgentService;

    /**
     * 餐食服务，用于 PERSONAL 模式空库前置检查。
     */
    private final ActivityService activityService;
    private final RelaxationSearchService relaxationSearchService;
    private final WeatherRecommendationService weatherRecommendationService;
    private final TimeExpressionParser timeExpressionParser;

    /**
     * 健康风险守卫，拦截医疗承诺/极端节食等高风险表述。
     */
    private final RiskGuardService riskGuardService;

    /**
     * 链路追踪服务，记录状态机事件和 Agent 调用到 agent_traces 表。
     */
    private final AgentTraceService agentTraceService;

    /**
     * 会话级锁 Map，key=sessionId，value=锁对象，保证同 session 串行写状态。
     */
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /**
     * Spring 构造器注入全部依赖。
     */
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
            TimeExpressionParser timeExpressionParser,
            RiskGuardService riskGuardService,
            AgentTraceService agentTraceService
    ) {
        this.sessionService = sessionService;                           // 注入消息落库服务
        this.sessionStateService = sessionStateService;                 // 注入会话状态服务
        this.intentAgentService = intentAgentService;                   // 注入意图识别服务
        this.intentReviseService = intentReviseService;                 // 注入意图矫正服务
        this.slotMergeService = slotMergeService;                       // 注入槽位合并服务
        this.slotOptionService = slotOptionService;                     // 注入槽位字典服务
        this.slotMutationService = slotMutationService;
        this.clarifyAgentService = clarifyAgentService;                 // 注入澄清 Agent 服务
        this.activitySearchService = activitySearchService;                     // 注入餐食检索服务
        this.activityRankService = activityRankService;                         // 注入餐食重排服务
        this.recommendResponseAgentService = recommendResponseAgentService; // 注入推荐应答 Agent 服务
        this.activityPlanService = activityPlanService;                         // 注入多餐规划服务
        this.planResponseAgentService = planResponseAgentService;       // 注入规划应答 Agent 服务
        this.activityService = activityService;                                 // 注入餐食服务
        this.relaxationSearchService = relaxationSearchService;                 // 注入相近活动回退检索
        this.weatherRecommendationService = weatherRecommendationService;       // 注入天气排序上下文
        this.timeExpressionParser = timeExpressionParser;
        this.riskGuardService = riskGuardService;             // 注入健康守卫
        this.agentTraceService = agentTraceService;                     // 注入链路追踪服务
    }

    /**
     * 同步处理一轮用户输入并返回完整推荐结果（HTTP 入口对应方法）。
     */
    public ChatResponse dietChat(Long userId, ChatRequest request) {
        // 生成本轮唯一 traceId，格式 trace_<32位hex>，贯穿整轮请求的所有 Trace 事件
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        // 校验 request 非空且 message 非空白，否则抛业务异常
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new CityException("用户问题不能为空");
        }
        // 校验 sourceMode 必填（PERSONAL 个人库 / PUBLIC 公共库），否则无法检索
        if (request.sourceMode() == null) {
            throw new CityException("sourceMode 不能为空，请选择 PERSONAL 或 PUBLIC");
        }

        // 从 DB 加载已有会话状态，或按 sessionId/userId 创建新会话，得到 slots/phase/lastRecommendedActivityIds 等
        SessionState initialState = sessionStateService.loadOrCreate(request.sessionId(), userId, request.sourceMode());

        // 开启 Trace 上下文；try-with-resources 结束时 TraceScope#close 会将整轮事件写入 agent_traces 表
        // 创建 TraceScope,进入 try. 执行整个请求,离开 try, 自动调用 close()
        try (AgentTraceService.TraceScope ignored = agentTraceService.openTrace(traceId, initialState.sessionId(), userId)) {
            try {
                // 记录请求开始时间（纳秒），用于最后计算整轮耗时
                long startedAt = System.nanoTime();
                // Trace 事件：REQUEST_RECEIVED | 阶段 HTTP | 输入=ChatRequest | 输出=初始 SessionState
                agentTraceService.recordEvent("REQUEST_RECEIVED", "HTTP", request, initialState);

                // 获取或创建该 sessionId 对应的锁对象，保证同一 session 并发请求串行执行
                Object lock = sessionLocks.computeIfAbsent(initialState.sessionId(), key -> new Object());
                synchronized (lock) {
                    // 在锁内执行完整状态机，处理本轮用户输入
                    ChatResponse response = handleTurn(userId, request, traceId, initialState);
                    // Trace 事件：REQUEST_FINISHED | 阶段 HTTP | 输入=ChatRequest | 输出=ChatResponse | 耗时 ms
                    agentTraceService.recordEvent("REQUEST_FINISHED", "HTTP", request, response, elapsedMs(startedAt));
                    // 将最终响应返回给 Controller
                    return response;
                }
            } catch (RuntimeException error) {
                // Trace 事件：REQUEST_FAILED | 阶段 HTTP | 输入=ChatRequest | 记录异常并将 Trace 标记 FAILED
                agentTraceService.recordError("REQUEST_FAILED", "HTTP", request, error);
                // 继续向上抛出，由全局异常处理器返回错误响应
                throw error;
            }
        }
    }

    /** 用户确认后才执行放宽检索；原始会话槽位不会被修改。 */
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

    /**
     * 在会话锁内执行完整状态机：记消息 → 前置校验 → 意图识别 → 路由分发。
     */
    private ChatResponse handleTurn(Long userId, ChatRequest request, String traceId, SessionState state) {
        // 从会话状态中取出 sessionId，后续落库和 Agent 调用都依赖它
        String sessionId = state.sessionId();
        SlotBundle contextSlots = contextSlots(request.context());
        // 页面上下文是用户的明确选择：城市/区域覆盖历史值，不能与历史城市做并集。
        if (!contextSlots.isEmpty()) {
            state = applyContextSlots(state, contextSlots);
            agentTraceService.recordEvent("CONTEXT_SLOTS_APPLIED", "SLOT", request.context(), contextSlots);
        }
        // 从会话状态中取出数据源模式（PERSONAL / PUBLIC）
        SourceMode sourceMode = state.sourceMode();

        // 将用户消息 INSERT 到 diet_messages 表，role=user，intent=null，关联 traceId
        sessionService.appendMessage(sessionId, "user", request.message(), null, traceId);

        // Trace 事件：USER_MESSAGE_RECORDED | 阶段 SESSION | 输入=用户原文 | 输出=sessionId+sourceMode
        agentTraceService.recordEvent("USER_MESSAGE_RECORDED", "SESSION", request.message(), Map.of("sessionId", sessionId, "sourceMode", sourceMode));

        // PERSONAL 模式且用户尚未录入任何个人活动时，自动降级到 PUBLIC 模式（混合模式）
        if (sourceMode == SourceMode.PERSONAL && !activityService.hasPersonalActivities(userId)) {
            // Trace 事件：PERSONAL_LIBRARY_EMPTY_FALLBACK_TO_PUBLIC | 阶段 ROUTE | 输入=userId | 输出=降级提示
            agentTraceService.recordEvent("PERSONAL_LIBRARY_EMPTY_FALLBACK_TO_PUBLIC", "ROUTE",
                Map.of("userId", userId, "originalSourceMode", sourceMode),
                Map.of("fallbackSourceMode", SourceMode.PUBLIC));
            // 降级到公共库，更新会话状态的 sourceMode
            state = state.withSourceMode(SourceMode.PUBLIC);
        }

        // 先保留原文解析结果作为 Agent 失败或未返回 time 操作时的兜底。
        TimeConstraint textParsedTime = timeExpressionParser.parse(request.message());

        // 意图识别：调用 IntentAgent：传入 sessionId、userId、用户原文、历史槽位、最近 3 条对话摘要
        IntentResult rawIntent = intentAgentService.recognize(sessionId, userId, request.message(), state.slots(), sessionService.recentConversationTurns(sessionId, userId, 3));
        // Trace 事件：INTENT_RECOGNIZED | 阶段 INTENT | 输入=用户原文 | 输出=IntentResult（intent/slots/confidence）
        agentTraceService.recordEvent("INTENT_RECOGNIZED", "INTENT", request.message(), rawIntent);

        // 调用 IntentReviseService，结合历史 phase/slots/lastRecommendedActivityIds 二次矫正意图
        IntentResult intent = intentReviseService.revise(state, rawIntent, request.message());
        TimeConstraint parsedTime = resolveTimeConstraint(intent, request.message(), textParsedTime);
        if (parsedTime.hasConstraint()) {
            state = state.withTimeConstraint(parsedTime);
            agentTraceService.recordEvent("TIME_CONSTRAINT_RESOLVED", "TIME", parsedTime.raw(), parsedTime);
        } else if (hasTimeClearOperation(intent) || timeExpressionParser.clearRequested(request.message())) {
            state = state.withTimeConstraint(TimeConstraint.empty());
            agentTraceService.recordEvent("TIME_CONSTRAINT_CLEARED", "TIME", request.message(), TimeConstraint.empty());
        }
        // Trace 事件：INTENT_REVISED | 阶段 INTENT | 输入=矫正前 rawIntent | 输出=矫正后 intent
        agentTraceService.recordEvent("INTENT_REVISED", "INTENT", rawIntent, intent);

        // Trace 事件：ROUTE_SELECTED | 阶段 ROUTE | 输入=最终 intent | 输出=路由目标 intent 枚举名
        agentTraceService.recordEvent("ROUTE_SELECTED", "ROUTE", intent, Map.of("route", intent.intent()));

        // 按最终意图枚举分发到对应分支处理器
        return switch (intent.intent()) {
            // 推荐或需澄清：走推荐主链路（澄清由 ClarifyAgent 内部决定）
            case MEAL_RECOMMENDATION, CLARIFY_NEEDED ->
                    handleRecommendation(sessionId, userId, request.message(), traceId, state, intent);
            // 调整上轮推荐：排除已推荐 ID，重跑推荐流水线
            case MEAL_ADJUST -> handleAdjust(sessionId, userId, request.message(), traceId, state, intent);
            // 多餐规划：按餐次拆分检索后统一包装
            case ACTIVITY_PLAN -> handlePlan(sessionId, userId, request.message(), traceId, state, intent);
            // 健康风险：返回 NutritionGuard 保守提示，不走推荐
            case HEALTH_RISK -> handleHealthRisk(sessionId, traceId, state);
            // 其他无关饮食的内容：返回固定引导文案
            case OTHER -> handleChitchat(sessionId, traceId, state);
        };
    }

    /** 优先使用 IntentAgent 提取的 time 操作，原文解析仅作兼容兜底。 */
    private TimeConstraint resolveTimeConstraint(IntentResult intent, String userInput, TimeConstraint textParsedTime) {
        if (intent != null && intent.operations() != null) {
            for (ConstraintOperation operation : intent.operations()) {
                if (operation == null || !"time".equals(operation.field()) || operation.op() == null) continue;
                if (operation.op() == ConstraintOperationType.CLEAR) return TimeConstraint.empty();
                if (operation.op() == ConstraintOperationType.SET) {
                    String raw = operation.raw() == null || operation.raw().isBlank() ? userInput : operation.raw();
                    TimeConstraint resolved = timeExpressionParser.parse(raw);
                    return resolved.hasConstraint() ? resolved : textParsedTime;
                }
            }
        }
        return textParsedTime;
    }

    private boolean hasTimeClearOperation(IntentResult intent) {
        return intent != null && intent.operations() != null
                && intent.operations().stream().anyMatch(operation -> operation != null
                && "time".equals(operation.field())
                && operation.op() == ConstraintOperationType.CLEAR);
    }

    /** 页面城市选择优先写入会话槽位，避免依赖模型从短回复中猜测城市。 */
    private SlotBundle contextSlots(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return SlotBundle.empty();
        }
        String city = contextValue(context, "city");
        String location = contextValue(context, "location");
        return slotOptionService.sanitize(new SlotBundle(
                city.isBlank() ? List.of() : List.of(city),
                location.isBlank() ? List.of() : List.of(location),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        ));
    }

    /**
     * 应用前端显式选择的上下文槽位。
     * 城市和位置属于页面级单选/定位条件，传入时覆盖历史值；未传入的维度保持不变。
     */
    private SessionState applyContextSlots(SessionState state, SlotBundle contextSlots) {
        SlotBundle historical = state.slots() == null ? SlotBundle.empty() : state.slots();
        SlotBundle context = contextSlots == null ? SlotBundle.empty() : contextSlots;
        SlotBundle applied = new SlotBundle(
                context.city().isEmpty() ? historical.city() : context.city(),
                context.location().isEmpty() ? historical.location() : context.location(),
                historical.mood(),
                historical.scene(),
                historical.budget(),
                historical.activityType(),
                historical.style(),
                historical.duration()
        );
        return state.withSlots(applied);
    }

    private String contextValue(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 推荐主链路：合并槽位 → ClarifyAgent 判追问 → 槽位足够则进入 completeRecommendation。
     */
    private ChatResponse handleRecommendation(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent) {
        // 将历史 slots 与 IntentAgent 本轮识别的 slots 合并（本轮非空覆盖，本轮空保留历史）
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());
        SlotMutation mutation = slotMutationService.apply(intent.operations(), userInput, mergedSlots, state.excludedSlots());
        mergedSlots = mutation.included();

        // Trace 事件：SLOTS_MERGED | 阶段 SLOT | 输入=stateSlots+intentSlots | 输出=mergedSlots
        agentTraceService.recordEvent("SLOTS_MERGED", "SLOT", Map.of("stateSlots", state.slots(), "intentSlots", intent.slots()), mergedSlots);

        // 基于合并槽位构建工作态：意图固定为 MEAL_RECOMMENDATION
        SessionState workingState = state.withIntent(Intent.MEAL_RECOMMENDATION).withSlots(mergedSlots)
                .withExcludedSlots(mutation.excluded());

        // 【重要】不能完全依靠agent的意图识别,在进入推荐之前,规则层面上也需要判断是否有足够的信息
        // 调用 ClarifyAgent：规则层先判缺失槽位，不足则 LLM 生成追问文案
        ClarifyResult clarify = clarifyAgentService.decide(sessionId, userInput, mergedSlots, workingState.timeConstraint());
        // Trace 事件：CLARIFY_DECISION | 阶段 CLARIFY | 输入=mergedSlots | 输出=ClarifyResult（ASK/READY）
        agentTraceService.recordEvent("CLARIFY_DECISION", "CLARIFY", mergedSlots, clarify);

        // 若 ClarifyResult.action == ASK，说明槽位不足，需要追问用户
        if (clarify.action() == ClarifyAction.ASK) {
            // 直接返回追问，不进入检索推荐
            return completeAsk(sessionId, traceId, workingState, clarify);
        }
        // 槽位足够：phase 切 RECOMMEND，excludeActivityIds 为空
        return completeRecommendation(sessionId, userId, userInput, traceId, workingState.withPhase(SessionPhase.RECOMMEND), List.of());
    }

    private ChatResponse completeAsk(String sessionId, String traceId, SessionState workingState, ClarifyResult clarify) {
        // 将会话 phase 切换为 CLARIFY，表示当前处于澄清等待用户回复状态
        SessionState clarifyState = workingState.withPhase(SessionPhase.CLARIFY);
        // 将澄清态会话状态 UPDATE 到 diet_sessions 表
        sessionStateService.save(clarifyState);

        // 将助手追问消息 INSERT 到 diet_messages，intent=CLARIFY_NEEDED
        sessionService.appendMessage(sessionId, "assistant", clarify.questionToAsk(), Intent.CLARIFY_NEEDED.name(), traceId);

        // 构造澄清型 ChatResponse，携带 missingSlots 供前端展示
        ChatResponse response = withConversationContext(
                ChatResponse.clarify(sessionId, traceId, clarify.questionToAsk(), clarify.missingSlots()), clarifyState);

        // Trace 事件：RESPONSE_READY | 阶段 CLARIFY | 输入=clarify | 输出=ChatResponse
        agentTraceService.recordEvent("RESPONSE_READY", "CLARIFY", clarify, response);

        // 直接返回追问，不进入检索推荐
        return response;
    }

    /**
     * 调整链路：合并槽位 → 取 excludeActivityIds → 重跑推荐流水线。
     */
    private ChatResponse handleAdjust(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent) {
        // 合并历史槽位与本轮 IntentAgent 识别的槽位
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());
        SlotMutation mutation = slotMutationService.apply(intent.operations(), userInput, mergedSlots, state.excludedSlots());
        mergedSlots = mutation.included();

        // 构建调整态工作会话：意图=MEAL_ADJUST，phase=RECOMMEND
        SessionState workingState = state.withIntent(Intent.MEAL_ADJUST)
                .withSlots(mergedSlots)
                .withExcludedSlots(mutation.excluded())
                .withPhase(SessionPhase.RECOMMEND);

        // 只有检索约束完全相同的“换一批”才排除上一批；日期、城市、预算、类型或排除条件变化都开启新结果集。
        String currentQueryKey = recommendationQueryKey(workingState);
        boolean queryChanged = !currentQueryKey.equals(state.recommendationQueryKey());
        List<Long> excludeActivityIds = queryChanged || state.lastRecommendedActivityIds() == null
                ? List.of()
                : state.lastRecommendedActivityIds();
        agentTraceService.recordEvent("ADJUST_CONTEXT_RESOLVED", "ADJUST", intent,
                traceMap("mergedSlots", mergedSlots, "excludeActivityIds", excludeActivityIds, "queryChanged", queryChanged));

        // 进入推荐流水线，仅排除已推荐餐食，实现换一批
        return completeRecommendation(sessionId, userId, userInput, traceId, workingState, excludeActivityIds);
    }

    /**
     * 活动规划链路：合并槽位 → 先做完整性澄清 → 解析时段 → 按时段拆分检索重排 → 规划应答包装。
     */
    private ChatResponse handlePlan(String sessionId, Long userId, String userInput, String traceId, SessionState state, IntentResult intent) {
        // 合并历史槽位与本轮槽位。规划请求也必须先复用普通推荐的最小信息完整性规则，
        // 避免“帮我安排一天行程”在缺少城市等关键约束时直接进入检索。
        SlotBundle mergedSlots = slotMergeService.merge(state.slots(), intent.slots());
        SessionState planContextState = state.withIntent(Intent.ACTIVITY_PLAN).withSlots(mergedSlots);

        ClarifyResult clarify = clarifyAgentService.decide(
                sessionId, userInput, mergedSlots, planContextState.timeConstraint());
        agentTraceService.recordEvent("PLAN_CLARIFY_DECISION", "CLARIFY", mergedSlots, clarify);

        if (clarify.action() == ClarifyAction.ASK) {
            // 保留 currentIntent=ACTIVITY_PLAN。下一轮用户只补“西安/200以内”等条件时，
            // IntentReviseService 会根据 CLARIFY + ACTIVITY_PLAN 恢复到规划链路。
            return completeAsk(sessionId, traceId, planContextState, clarify);
        }

        List<String> planActivityTimes = activityPlanService.resolveActivityTimes(mergedSlots, planContextState.timeConstraint());
        // 规划态 slots 显式写入目标时段，便于后续轮次与 Trace 观察
        SlotBundle planSlots = new SlotBundle(
                mergedSlots.city(),
                mergedSlots.location(),
                mergedSlots.mood(),
                mergedSlots.scene(),
                mergedSlots.budget(),
                mergedSlots.activityType(),
                mergedSlots.style(),
                mergedSlots.duration()
        );
        // Trace 事件：PLAN_CONTEXT_RESOLVED | 阶段 PLAN | 输入=intent | 输出=planSlots+periods
        agentTraceService.recordEvent(
                "PLAN_CONTEXT_RESOLVED",
                "PLAN",
                intent,
                traceMap("mergedSlots", mergedSlots, "planActivityTimes", planActivityTimes, "planSlots", planSlots)
        );

        SessionState workingState = planContextState.withSlots(planSlots).withPhase(SessionPhase.PLAN);
        return completePlan(sessionId, userId, userInput, traceId, workingState, planActivityTimes);
    }

    /**
     * 多餐规划流水线：按餐次 search/rank 各取一款 → PlanResponseAgent → Guard → 落库。
     */
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
                "ACTIVITY_PLAN_SEARCHED",
                "PLAN",
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
                "PLAN_RESULT_BUILT",
                "PLAN",
                Map.of("strategy", Intent.ACTIVITY_PLAN.name(), "plannedMeals", planTrace),
                recommend
        );

        ResponseResult response = merged.response();
        agentTraceService.recordEvent("PLAN_RESPONSE_AGENT_RESULT", "RESPONSE", recommend, response);

        RiskGuardResult guard = riskGuardService.check(userInput, Intent.ACTIVITY_PLAN, recommend, response);
        agentTraceService.recordEvent(
                "NUTRITION_GUARD_CHECKED",
                "GUARD",
                Map.of("intent", Intent.ACTIVITY_PLAN, "response", response),
                guard
        );

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

    /** 用持久化的规范化约束判断本轮是否仍是同一个推荐结果集。 */
    private String recommendationQueryKey(SessionState state) {
        if (state == null) return "";
        return String.valueOf(state.sourceMode()) + "|" + state.slots() + "|" + state.excludedSlots()
                + "|" + state.timeConstraint();
    }

    /**
     * 健康风险分支：返回 NutritionGuard 保守提示，不走推荐链路。
     */
    private ChatResponse handleHealthRisk(String sessionId, String traceId, SessionState state) {
        // 构造纯文本响应，内容为 conservativeMessage 固定文案
        ResponseResult response = ResponseResult.textOnly(riskGuardService.conservativeMessage());
        // 走纯文本完成分支，intent 标记为 HEALTH_RISK
        return completeTextOnly(sessionId, traceId, state, Intent.HEALTH_RISK, response);
    }

    /**
     * 闲聊分支：返回固定引导文案，不调用 LLM。
     */
    private ChatResponse handleChitchat(String sessionId, String traceId, SessionState state) {
        // 构造纯文本响应，内容为 CHITCHAT_REPLY 常量
        ResponseResult response = ResponseResult.textOnly(CHITCHAT_REPLY);
        // 走纯文本完成分支，intent 标记为 CHITCHAT
        return completeTextOnly(sessionId, traceId, state, Intent.OTHER, response);
    }

    /**
     * 完整推荐流水线：检索 → 重排 → LLM 生成理由与口语回复 → Guard 审查 → 持久化并返回。
     */
    private ChatResponse completeRecommendation(String sessionId, Long userId, String userInput, String traceId, SessionState state, List<Long> excludeActivityIds) {
        return completeRecommendation(sessionId, userId, userInput, traceId, state, excludeActivityIds, null);
    }

    private ChatResponse completeRecommendation(String sessionId, Long userId, String userInput, String traceId,
                                                 SessionState state, List<Long> excludeActivityIds,
                                                 RelaxationSearchService.SearchResult selectedRelaxation) {
        // 只对“今天/明天/后天”的请求使用短期预报，周末等无明确日期的需求不猜测天气。
        WeatherRecommendationContext weather = weatherRecommendationService.resolve(userInput, state.slots());
        agentTraceService.recordEvent("WEATHER_CONTEXT_RESOLVED", "RANK", state.slots(), weather);
        // 构造检索请求：sourceMode + userId + 当前 slots + excludeActivityIds（检索层暂不使用 exclude，在 Rank 层过滤）
        List<ActivityItem> candidates = selectedRelaxation == null
                ? activitySearchService.search(new ActivitySearchRequest(state.sourceMode(), userId, state.slots(), excludeActivityIds, state.timeConstraint(), state.excludedSlots()))
                : selectedRelaxation.ranked();
        // Trace 事件：ACTIVITY_SEARCHED | 阶段 SEARCH | 输入=slots | 输出=候选数量+candidates 列表
        agentTraceService.recordEvent("ACTIVITY_SEARCHED", "SEARCH", state.slots(), Map.of("candidateCount", candidates.size(), "candidates", candidates));

        // 构造排序请求：候选列表 + slots + excludeActivityIds，返回 top10
        List<ActivityItem> ranked = selectedRelaxation == null
                ? activityRankService.rank(new ActivityRankRequest(candidates, state.slots(), excludeActivityIds), weather)
                : activityRankService.rank(new ActivityRankRequest(candidates, state.slots(), excludeActivityIds), weather);
        // Trace 事件：ACTIVITY_RANKED | 阶段 RANK | 输入=excludeActivityIds | 输出=重排后数量+ranked 列表
        agentTraceService.recordEvent("ACTIVITY_RANKED", "RANK", Map.of("excludeActivityIds", excludeActivityIds), Map.of("rankedCount", ranked.size(), "ranked", ranked));

        // 结果为空时，按 sourceMode 返回不同的空库提示文案
        if (ranked.isEmpty()) {
            // PERSONAL 模式：先尝试降级到公共库重新搜索（混合模式）
            if (state.sourceMode() == SourceMode.PERSONAL) {
                // Trace 事件：PERSONAL_NO_MATCH_FALLBACK | 阶段 RECOMMEND | 输入=state | 输出=降级提示
                agentTraceService.recordEvent("PERSONAL_NO_MATCH_FALLBACK", "RECOMMEND", state,
                    Map.of("fallbackSourceMode", SourceMode.PUBLIC));

                // 用公共库重新搜索
                List<ActivityItem> publicCandidates = activitySearchService.search(
                    new ActivitySearchRequest(SourceMode.PUBLIC, userId, state.slots(), excludeActivityIds, state.timeConstraint(), state.excludedSlots()));
                agentTraceService.recordEvent("ACTIVITY_SEARCHED_PUBLIC_FALLBACK", "SEARCH", state.slots(),
                    Map.of("candidateCount", publicCandidates.size(), "candidates", publicCandidates));

                // 重新排序
                List<ActivityItem> publicRanked = activityRankService.rank(
                    new ActivityRankRequest(publicCandidates, state.slots(), excludeActivityIds), weather);
                agentTraceService.recordEvent("ACTIVITY_RANKED_PUBLIC_FALLBACK", "RANK",
                    Map.of("excludeActivityIds", excludeActivityIds),
                    Map.of("rankedCount", publicRanked.size(), "ranked", publicRanked));

                // 如果公共库有结果，继续推荐流程（标记为来自公共库）
                if (!publicRanked.isEmpty()) {
                    ranked = publicRanked;
                    // 更新 state 为 PUBLIC 模式，后续流程会标记推荐来源
                    state = state.withSourceMode(SourceMode.PUBLIC);
                    agentTraceService.recordEvent("FALLBACK_SUCCESS", "RECOMMEND",
                        Map.of("fallbackSourceMode", SourceMode.PUBLIC, "rankedCount", ranked.size()), null);
                } else {
                    // 公共库也没结果，返回提示
                    ResponseResult empty = ResponseResult.textOnly(
                        "你的个人活动库和公共推荐里都暂时没有很匹配的，可以试试调整时间、预算或氛围描述。");
                    agentTraceService.recordEvent("NO_ACTIVITY_MATCHED_BOTH", "RECOMMEND", state, empty);
                    return completeTextOnly(sessionId, traceId, state, state.currentIntent(), empty);
                }
            } else {
                // PUBLIC 模式没结果
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

        // 调用 RecommendResponseAgent：top3 候选 + 用户原文 + slots → 推荐理由 + speechText + 卡片
        RecommendResponseAgentService.Result merged = recommendResponseAgentService.recommendAndRespond(
                sessionId, userInput, state.sourceMode(), state.slots(), ranked, weather);

        // 从结果中取出 RecommendResult（含 recommendations 列表和 needDisclaimer 标记）
        RecommendResult recommend = merged.recommend();
        // Trace 事件：RECOMMEND_RESULT_BUILT | 阶段 RECOMMEND | 输入=strategy+ranked | 输出=RecommendResult
        String strategy = state.currentIntent() == null ? Intent.MEAL_RECOMMENDATION.name() : state.currentIntent().name();
        agentTraceService.recordEvent("RECOMMEND_RESULT_BUILT", "RECOMMEND", Map.of("strategy", strategy, "ranked", ranked), recommend);

        // 从结果中取出 ResponseResult（含 speechText、displayBlocks、nextAction）
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
        // Trace 事件：RESPONSE_AGENT_RESULT | 阶段 RESPONSE | 输入=recommend | 输出=ResponseResult
        agentTraceService.recordEvent("RESPONSE_AGENT_RESULT", "RESPONSE", recommend, response);

        // 调用 riskGuardService 检查用户输入 + 最终回复是否含健康风险关键词
        RiskGuardResult guard = riskGuardService.check(userInput, state.currentIntent(), recommend, response);
        // Trace 事件：NUTRITION_GUARD_CHECKED | 阶段 GUARD | 输入=intent+response | 输出=GuardResult（passed/reasons）
        agentTraceService.recordEvent("NUTRITION_GUARD_CHECKED", "GUARD", Map.of("intent", state.currentIntent(), "response", response), guard);

        // Guard 未通过时，用 conservativeMessage 替换 speechText，丢弃原 LLM 回复
        if (!guard.passed()) {
            // 重建纯文本 ResponseResult，内容为 guard.rewriteSuggestion()
            response = ResponseResult.textOnly(guard.rewriteSuggestion());
            // Trace 事件：NUTRITION_GUARD_REWRITTEN | 阶段 GUARD | 输入=guard | 输出=替换后的 response
            agentTraceService.recordEvent("NUTRITION_GUARD_REWRITTEN", "GUARD", guard, response);
        } else {
            // Guard 通过时也记录事件，表示未改写（COMPLIANCE_GUARD_REWRITTEN 为历史命名，语义=合规检查通过）
            agentTraceService.recordEvent("COMPLIANCE_GUARD_REWRITTEN", "GUARD", null, response);
        }

        // 从推荐结果中提取本轮推荐的 activityId 列表，追加到 lastRecommendedActivityIds 供下轮调整累积排除
        List<Long> lastIds = recommend.recommendations().stream().map(option -> option.itemId()).toList();
        String queryKey = recommendationQueryKey(state);
        // 约束已变化时以本次结果重置排除历史；只有相同约束的“换一批”才累积排除。
        SessionState savedState = queryKey.equals(state.recommendationQueryKey())
                ? state.appendLastRecommendations(lastIds)
                : state.withLastRecommendations(lastIds);
        savedState = savedState.withRecommendationQueryKey(queryKey);

        // 将更新后的会话状态 UPDATE 到 diet_sessions 表
        sessionStateService.save(savedState);

        // 将助手回复 INSERT 到 diet_messages，intent=当前意图名，content=speechText
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), state.currentIntent().name(), traceId);

        // 构造最终 ChatResponse：含 speechText、餐食卡片 displayBlocks、nextAction=WAIT_USER
        ChatResponse chatResponse = withConversationContext(
                ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction()), savedState);

        // Trace 事件：RESPONSE_READY | 阶段 RESPONSE | 输入=savedState | 输出=ChatResponse
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);

        // 返回带推荐卡片的完整响应
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

    /** 将最终会话约束随响应返回，使前端不必猜测系统当前记住了什么。 */
    private ChatResponse withConversationContext(ChatResponse response, SessionState state) {
        if (response == null || state == null) return response;
        response.appliedSlots(state.slots());
        response.excludedSlots(state.excludedSlots());
        response.timeConstraint(state.timeConstraint());
        return response;
    }

    /**
     * 纯文本分支的统一收尾：更新 intent → 保存状态 → 落库消息 → 返回 ChatResponse。
     */
    private ChatResponse completeTextOnly(String sessionId, String traceId, SessionState state, Intent intent, ResponseResult response) {
        // 将会话 currentIntent 更新为传入的 intent 枚举
        SessionState savedState = state.withIntent(intent);
        // 将更新后的会话状态 UPDATE 到 diet_sessions 表
        sessionStateService.save(savedState);

        // 将助手纯文本回复 INSERT 到 diet_messages
        sessionService.appendMessage(sessionId, "assistant", response.speechText(), intent.name(), traceId);

        // 构造 ChatResponse（无餐食卡片，displayBlocks 为空）
        ChatResponse chatResponse = ChatResponse.answer(sessionId, traceId, response.speechText(), response.displayBlocks(), response.nextAction());

        // Trace 事件：RESPONSE_READY | 阶段 RESPONSE | 输入=intent+savedState | 输出=ChatResponse
        agentTraceService.recordEvent("RESPONSE_READY", "RESPONSE", Map.of("intent", intent, "state", savedState), chatResponse);

        // 返回纯文本响应
        return chatResponse;
    }

    /**
     * 将纳秒级开始时间戳转为毫秒耗时。
     */
    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * 构造 Trace payload Map，支持 key-value 交替传入，允许 value 为 null。
     */
    private Map<String, Object> traceMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 每两个元素为一组 key-value，步长 2 遍历
        for (int i = 0; i + 1 < entries.length; i += 2) {
            // 将 key 转 String 后与 value 放入 Map
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }
}

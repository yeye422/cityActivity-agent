package com.city.service.intent;

import com.city.agent.factory.AgentFactory;
import com.city.enums.ConstraintOperationType;
import com.city.enums.Intent;
import com.city.enums.PreferencePolarity;
import com.city.enums.TemporalMode;
import com.city.model.ConstraintOperation;
import com.city.model.ConversationTurn;
import com.city.model.IntentResult;
import com.city.model.MemoryMutationProposal;
import com.city.model.SlotBundle;
import com.city.model.TemporalMutation;
import com.city.model.TimeConstraint;
import com.city.model.UserGoalPatch;
import com.city.service.slot.SlotOptionService;
import com.city.service.trace.AgentTraceService;
import com.city.util.LlmJsonService;
import com.city.util.SlotJsonPicker;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IntentAgent 的业务适配层：把自然语言转换成 Orchestrator 可执行的结构化 Patch。
 *
 * <p>一次识别产出业务路由、ConstraintOperation、TemporalMutation，以及可选的长期记忆 Proposal。
 * 九维普通条件的新增、替换、排除和取消限制全部通过 operations 表达；时间变化只通过 temporal 表达。
 * 历史普通状态由 SlotMutationService 确定性更新，历史时间状态由 TimeResolutionService 确定性更新。</p>
 *
 * <p>Intent 只负责推荐、调整、规划、其他四类业务路由；是否澄清以及是否需要安全拦截由后端独立判断。
 * LLM 不能直接制造任意槽位值：所有 operation value 都会经过 SlotOptionService 提供的启用字典过滤。
 * 模型调用或 JSON 结构解析失败时，Java fallback 也先生成 ConstraintOperation，再进入同一 mutation 链。</p>
 */
@Service
public class IntentAgentService {

    /** 相对日期解析统一以业务时区为基准，避免服务器所在时区改变“本周六/明天”的含义。 */
    private static final ZoneId TIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final AgentFactory agentFactory;
    private final LlmJsonService llmJsonService;
    private final SlotOptionService slotOptionService;
    private final AgentTraceService agentTraceService;
    private final String modelName;

    public IntentAgentService(
            AgentFactory agentFactory,
            LlmJsonService llmJsonService,
            SlotOptionService slotOptionService,
            AgentTraceService agentTraceService,
            @Value("${city.llm.main-model:qwen-max}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.slotOptionService = slotOptionService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    /**
     * 执行一轮意图识别。
     *
     * <p>链路为：加载槽位字典 → 获取会话级 IntentAgent → 清空 Agent 内存 → 构造显式上下文 Prompt
     * → 调用模型并记录 Trace → JSON 解析/字典过滤。任何一步失败都收敛到 fallback，保证主聊天链路可继续。</p>
     *
     * @param sessionId 当前会话 ID，用于隔离 Agent 实例和 Trace
     * @param userId 当前用户 ID
     * @param userInput 本轮用户原文
     * @param knownSlots 已持久化的九维条件，只用于模型理解上下文
     * @param knownTimeConstraint 已持久化的时间条件
     * @param recentHistory 最近若干轮短期上下文
     * @return 本轮结构化语义 Patch
     */
    public IntentResult recognize(
            String sessionId,
            Long userId,
            String userInput,
            SlotBundle knownSlots,
            TimeConstraint knownTimeConstraint,
            List<ConversationTurn> recentHistory
    ) {
        return recognize(sessionId, userId, userInput, knownSlots, knownTimeConstraint, recentHistory, List.of());
    }

    public IntentResult recognize(
            String sessionId,
            Long userId,
            String userInput,
            SlotBundle knownSlots,
            TimeConstraint knownTimeConstraint,
            List<ConversationTurn> recentHistory,
            List<String> knownGoals
    ) {
        try {
            // 字典既注入 Prompt，也用于解析后的白名单过滤，形成输入提示 + 输出约束双保险。
            Map<String, List<String>> slotOptions = slotOptionService.findAllOptions();
            ReActAgent agent = agentFactory.get(sessionId).intent();

            // 历史对话由后端显式放进 Prompt；清空 AgentScope 模型可见上下文，避免出现两套上下文来源。
            agent.clearContext((String) null, (String) null);
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "IntentAgent",
                    modelName,
                    agent,
                    buildUserPrompt(userId, sessionId, userInput, knownSlots, knownTimeConstraint, recentHistory, slotOptions, knownGoals)
            );
            return parseResult(response.getTextContent(), slotOptions);
        } catch (Exception ignored) {
            // fallback 仍尽量读取数据库字典；字典服务也不可用时退化为空字典，禁止凭空制造槽位。
            Map<String, List<String>> fallbackOptions;
            try {
                fallbackOptions = slotOptionService.findAllOptions();
            } catch (Exception unavailable) {
                fallbackOptions = Map.of();
            }
            return fallback(userInput, fallbackOptions);
        }
    }

    /**
     * 构造 IntentAgent 的单轮 Prompt。
     *
     * <p>当前已生效状态只作为理解指代的上下文；模型输出始终是本轮 Patch，而不是 SessionState 快照。</p>
     */
    private String buildUserPrompt(
            Long userId,
            String sessionId,
            String userInput,
            SlotBundle knownSlots,
            TimeConstraint knownTimeConstraint,
            List<ConversationTurn> recentHistory,
            Map<String, List<String>> slotOptions
    ) {
        return buildUserPrompt(userId, sessionId, userInput, knownSlots, knownTimeConstraint,
                recentHistory, slotOptions, List.of());
    }

    private String buildUserPrompt(
            Long userId,
            String sessionId,
            String userInput,
            SlotBundle knownSlots,
            TimeConstraint knownTimeConstraint,
            List<ConversationTurn> recentHistory,
            Map<String, List<String>> slotOptions,
            List<String> knownGoals
    ) {
        ZonedDateTime now = ZonedDateTime.now(TIME_ZONE);
        TimeConstraint safeTime = knownTimeConstraint == null ? TimeConstraint.empty() : knownTimeConstraint;
        return """
                ## 当前时间上下文
                currentDateTime: %s
                currentDate: %s
                currentDayOfWeek: %s
                timezone: %s
                所有相对日期基于 currentDate 计算；一周按周一到周日定义。

                ## 会话上下文
                userId: %s
                sessionId: %s
                最近对话: %s
                当前已生效九维条件: %s
                当前已生效开放语义目标: %s
                当前已生效时间条件: %s
                可用标准标签: %s
                当前用户消息: %s

                ## 本轮输出约束
                - 输出的是当前消息带来的语义 Patch，不是当前完整会话状态快照。
                - intent 只能是 ACTIVITY_RECOMMENDATION、ACTIVITY_ADJUST、ACTIVITY_PLAN、OTHER。
                - 信息不足不是独立 intent；主任务是找活动时仍输出 ACTIVITY_RECOMMENDATION，后端决定是否追问。
                - 安全风险不是独立 intent；带风险的业务请求仍按主业务 intent 输出，纯安全咨询输出 OTHER，后端 RiskGuard 统一处理。
                - operations 是九维普通属性唯一的状态变更协议；历史已生效值不要重复写入 operations。
                - 普通正向新增使用 ADD；明确“改成/换成/只要”使用 SET；明确排除使用 REMOVE；明确取消限制使用 CLEAR。
                - operations 只能使用九维字段：city、location、experienceGoal、companion、budget、activityType、style、duration、feature。
                - 能准确映射到标准字典的需求必须写 operations，包括 experienceGoal、companion、style；这些标签会参与 MySQL 过滤。
                - operation values 只能使用可用标准标签；CLEAR 的 values 必须为 []。
                - 无法准确映射到九维标准字典的开放体验目标写 userGoalPatch={op,values}，每次仅表达本轮变更。
                - userGoalPatch 的 op 支持 ADD/SET/REMOVE/CLEAR；无变化输出 null；禁止把已有标准标签重复写进开放目标。
                - 无法核实的安全、无障碍等强制限制不能软化为保证满足的 UserGoal；应保留原文供后续核验或澄清。
                - location 只表示地理区域；“近地铁/交通方便”等使用 feature。
                - duration 只表示活动自身持续时间；用户自己的可用时间只写 temporal。
                - 时间、日期、上午/下午/晚上等变化只能写 temporal，绝不能写 operations。
                - 当前消息没有修改某个普通字段时，不为该字段生成 operation。
                - 纯“换一批”必须是 ACTIVITY_ADJUST + operations=[] + userGoalPatch=null + temporal KEEP/KEEP。
                - memoryProposals 只用于用户明确表达长期偏好/长期排除，例如“以后都喜欢安静的展览”“以后不要户外”。
                - 本次预算、日期、地点、同行人、活动时长等一次性上下文绝不能写 memoryProposals。
                - memoryProposals 每项必须包含 slotName、slotValue、polarity(PREFER|AVOID)、explicitLongTerm=true、raw。
                - 最终只输出合法 JSON，顶层只能包含 intent、operations、userGoalPatch、temporal、memoryProposals、confidence。
                """.formatted(
                now.toLocalDateTime(),
                now.toLocalDate(),
                now.getDayOfWeek(),
                TIME_ZONE,
                userId,
                sessionId,
                recentHistory,
                knownSlots == null ? SlotBundle.empty() : knownSlots,
                knownGoals == null ? List.of() : knownGoals,
                safeTime,
                slotOptions,
                userInput
        );
    }

    /** 将模型 JSON 统一转换成 IntentResult；任一关键结构异常会抛出并由 recognize() 进入 fallback。 */
    private IntentResult parseResult(String content, Map<String, List<String>> slotOptions) {
        JsonNode root = llmJsonService.parseObject(content);
        Intent intent = parseIntent(root.path("intent").asText(null));
        double confidence = root.path("confidence").asDouble(0.5);
        return new IntentResult(
                intent,
                confidence,
                parseOperations(root.path("operations"), slotOptions),
                parseTemporal(root.path("temporal")),
                parseMemoryProposals(root.path("memoryProposals"), slotOptions),
                false,
                parseUserGoalPatch(root.path("userGoalPatch"), slotOptions)
        );
    }

    /** 不将可标准化标签或空泛结构混入开放目标。 */
    private UserGoalPatch parseUserGoalPatch(JsonNode node, Map<String, List<String>> options) {
        if (node == null || !node.isObject()) return null;
        try {
            ConstraintOperationType op = ConstraintOperationType.valueOf(
                    node.path("op").asText("").toUpperCase(Locale.ROOT));
            if (op == ConstraintOperationType.CLEAR) return new UserGoalPatch(op, List.of());
            JsonNode values = node.path("values");
            if (!values.isArray()) return null;
            java.util.Set<String> standard = new java.util.HashSet<>();
            options.values().forEach(standard::addAll);
            List<String> goals = new ArrayList<>();
            for (JsonNode value : values) {
                if (!value.isTextual()) continue;
                String goal = value.asText().trim();
                if (goal.isBlank() || goal.length() > 160 || standard.contains(goal)) continue;
                if (!goals.contains(goal)) goals.add(goal);
                if (goals.size() >= 8) break;
            }
            return goals.isEmpty() ? null : new UserGoalPatch(op, goals);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private List<MemoryMutationProposal> parseMemoryProposals(
            JsonNode node,
            Map<String, List<String>> options
    ) {
        if (node == null || !node.isArray()) return List.of();
        List<MemoryMutationProposal> result = new ArrayList<>();
        for (JsonNode item : node) {
            String slotName = item.path("slotName").asText("").trim();
            String slotValue = item.path("slotValue").asText("").trim();
            if (!SlotOptionService.SLOT_NAMES.contains(slotName)) continue;
            if (!options.getOrDefault(slotName, List.of()).contains(slotValue)) continue;
            try {
                PreferencePolarity polarity = PreferencePolarity.valueOf(
                        item.path("polarity").asText("PREFER").toUpperCase(Locale.ROOT));
                result.add(new MemoryMutationProposal(
                        slotName,
                        slotValue,
                        polarity,
                        item.path("explicitLongTerm").asBoolean(false),
                        item.path("raw").asText("")
                ));
            } catch (IllegalArgumentException ignored) {
                // 单条非法长期记忆建议直接丢弃。
            }
        }
        return List.copyOf(result);
    }

    /**
     * 解析模型的 temporal Patch。结构非法时返回 KEEP/KEEP，后续 TimeResolutionService 再结合原文决定是否 fallback/澄清。
     */
    private TemporalMutation parseTemporal(JsonNode node) {
        if (node == null || !node.isObject()) return TemporalMutation.keep();
        try {
            TemporalMode dateMode = TemporalMode.valueOf(node.path("dateMode").asText("KEEP").toUpperCase(Locale.ROOT));
            TemporalMode timeMode = TemporalMode.valueOf(node.path("timeMode").asText("KEEP").toUpperCase(Locale.ROOT));
            return new TemporalMutation(
                    node.path("raw").asText(""),
                    dateMode,
                    parseDate(node.path("dateStart")),
                    parseDate(node.path("dateEnd")),
                    timeMode,
                    parseTime(node.path("timeStart")),
                    parseTime(node.path("timeEnd")),
                    node.path("approximate").asBoolean(false),
                    node.path("confidence").asDouble(0.5)
            );
        } catch (Exception ignored) {
            return TemporalMutation.keep();
        }
    }

    private LocalDate parseDate(JsonNode node) {
        if (node == null || node.isNull() || node.asText("").isBlank()) return null;
        return LocalDate.parse(node.asText());
    }

    private LocalTime parseTime(JsonNode node) {
        if (node == null || node.isNull() || node.asText("").isBlank()) return null;
        return LocalTime.parse(node.asText());
    }

    /** 缺失或非法 intent 视为模型结构解析失败，交给 recognize() 的 fallback 路径统一处理。 */
    private Intent parseIntent(String rawIntent) {
        if (rawIntent == null || rawIntent.isBlank()) {
            throw new IllegalArgumentException("intent missing");
        }
        return Intent.valueOf(rawIntent);
    }

    /**
     * 解析 SET/ADD/REMOVE/CLEAR 操作。
     * 非九维字段、非法 op、没有有效 value 的非 CLEAR 操作都会被丢弃；单个坏 operation 不影响本轮其他结果。
     */
    private List<ConstraintOperation> parseOperations(JsonNode node, Map<String, List<String>> options) {
        if (node == null || !node.isArray()) return List.of();
        List<ConstraintOperation> result = new ArrayList<>();
        for (JsonNode item : node) {
            String field = item.path("field").asText("").trim();
            String opText = item.path("op").asText("").trim();
            if (!SlotOptionService.SLOT_NAMES.contains(field)) continue;
            try {
                ConstraintOperationType op = ConstraintOperationType.valueOf(opText.toUpperCase(Locale.ROOT));
                List<String> values = SlotJsonPicker.pick(item, "values", optionsFor(field, options));
                if (op != ConstraintOperationType.CLEAR && values.isEmpty()) continue;
                result.add(new ConstraintOperation(field, op, values, item.path("raw").asText("")));
            } catch (IllegalArgumentException ignored) {
                // 单个无效 operation 不影响本轮其他结构化结果。
            }
        }
        return List.copyOf(result);
    }

    /** SlotJsonPicker 固定读取 values 字段，因此把某一维字典包装成其期望的 Map 结构。 */
    private Map<String, List<String>> optionsFor(String field, Map<String, List<String>> options) {
        return Map.of("values", options.getOrDefault(field, List.of()));
    }

    /**
     * 仅在 Agent 调用或结构解析失败后执行的保底结果。
     * fallback=true 会被后续 Trace/评估识别，避免把规则兜底当成正常模型能力。
     */
    private IntentResult fallback(String userInput, Map<String, List<String>> options) {
        return new IntentResult(
                fallbackIntent(userInput),
                0.2,
                fallbackOperations(userInput, options),
                TemporalMutation.keep(),
                true
        );
    }

    /**
     * 模型失败时把高确定性的普通槽位语义统一转换成 operations。
     * CLEAR/REMOVE 优先；其余原文直接命中的合法字典值按 ADD 处理，不再生成独立 slots 快照。
     */
    private List<ConstraintOperation> fallbackOperations(String userInput, Map<String, List<String>> options) {
        String text = userInput == null ? "" : userInput.replaceAll("\\s+", "");
        if (text.isBlank()) return List.of();

        List<ConstraintOperation> result = new ArrayList<>();
        for (String field : SlotOptionService.SLOT_NAMES) {
            if (fallbackClear(text, field)) {
                result.add(new ConstraintOperation(field, ConstraintOperationType.CLEAR, List.of(), text));
                continue;
            }
            for (String value : options.getOrDefault(field, List.of())) {
                if (isNegativePreference(text, value)) {
                    result.add(new ConstraintOperation(field, ConstraintOperationType.REMOVE, List.of(value), text));
                } else if (text.contains(value)) {
                    result.add(new ConstraintOperation(field, ConstraintOperationType.ADD, List.of(value), text));
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean isNegativePreference(String text, String value) {
        return text.contains("不要" + value)
                || text.contains("不想" + value)
                || text.contains("不想看" + value)
                || text.contains("别" + value);
    }

    /** fallback CLEAR 只覆盖少量高确定性表达，避免规则兜底重新变成第二套语义解析器。 */
    private boolean fallbackClear(String text, String field) {
        return switch (field) {
            case "city" -> text.contains("城市不限") || text.contains("地点不限");
            case "location" -> text.contains("区域不限");
            case "budget" -> text.contains("预算不限") || text.contains("不限制预算");
            case "style" -> text.contains("风格不限");
            case "activityType" -> text.contains("类型不限") || text.contains("活动不限");
            case "duration" -> text.contains("时长不限") || text.contains("活动多久都行");
            case "feature" -> text.contains("特征不限") || text.contains("室内户外都行");
            default -> false;
        };
    }

    /**
     * 关键词 Intent 判断只存在于模型失败后的 fallback 路径。
     * fallback 只负责四类业务路由，不承担澄清决策和风险分类。
     */
    private Intent fallbackIntent(String userInput) {
        if (userInput == null || userInput.isBlank()) return Intent.OTHER;
        String text = userInput.replaceAll("\\s+", "");

        // 纯安全咨询仍是 OTHER，真正的风险拦截统一由后端 RiskGuard 处理。
        if (isPureSafetyQuestion(text)) return Intent.OTHER;

        // 明确修改/排除/取消约束时按调整处理；没有历史结果时 IntentRevise 会确定性降级为首次推荐。
        if (containsAny(text,
                "换一批", "换个", "换成", "改成", "改为", "改到", "改看",
                "不要", "不想", "清空", "取消限制",
                "预算不限", "不限制预算", "类型不限", "活动不限",
                "便宜点", "近一点", "安静点")) {
            return Intent.ACTIVITY_ADJUST;
        }

        if (containsActivityPlanSignal(text)) return Intent.ACTIVITY_PLAN;
        if (containsAny(text, "你是谁", "你是AI", "你好")) return Intent.OTHER;

        // 模型失败时，明确活动词或时间表达至少保持在推荐业务内；缺什么由后端 ClarifyRule/TimeResolution 决定。
        if (containsAny(text,
                "去哪", "去哪里", "玩什么", "活动", "展览", "电影", "演出", "运动", "探店", "推荐",
                "爬山", "徒步", "露营", "半天", "一天", "一日", "全天", "一整天", "有空", "都行", "都可以",
                "今天", "明天", "后天", "本周", "这周", "下周", "周末",
                "周一", "周二", "周三", "周四", "周五", "周六", "周日", "周天",
                "上午", "早上", "中午", "下午", "晚上", "今晚", "凌晨", "几点", "时间", "时段")) {
            return Intent.ACTIVITY_RECOMMENDATION;
        }
        return Intent.OTHER;
    }

    /** 仅区分“纯安全咨询”和“带风险条件的推荐需求”，不负责判断是否应该放行。 */
    private boolean isPureSafetyQuestion(String text) {
        boolean hasRiskSignal = containsAny(text,
                "暴雨", "台风", "雷暴", "极端天气", "偏远", "无人区", "深夜独自", "凌晨一个人",
                "酒后驾驶", "酒驾", "醉驾", "翻越围栏", "违法进入", "擅闯",
                "未成年人", "儿童");
        boolean asksSafety = containsAny(text,
                "安全吗", "安全么", "安全吗", "可以吗", "能不能", "能吗", "合适吗", "行不行");
        boolean asksRecommendation = containsAny(text,
                "推荐", "找几个", "找点", "有什么活动", "安排", "规划", "换成", "改成");
        return hasRiskSignal && asksSafety && !asksRecommendation;
    }

    /** fallback 中识别少量明确的“需要多时段规划”表达。 */
    private boolean containsActivityPlanSignal(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        String text = userInput.replaceAll("\\s+", "");
        return containsAny(text,
                "活动规划", "一日行程", "半日行程", "行程",
                "帮我安排", "给我安排", "帮我规划", "给我规划",
                "安排一下", "规划一下", "排一下", "怎么安排", "如何安排",
                "周末安排", "从上午到晚上", "从早到晚");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}

package com.city.service.intent;

import com.city.agent.factory.AgentFactory;
import com.city.enums.ConstraintOperationType;
import com.city.enums.Intent;
import com.city.enums.TemporalMode;
import com.city.model.ConstraintOperation;
import com.city.model.ConversationTurn;
import com.city.model.IntentResult;
import com.city.model.SlotBundle;
import com.city.model.TemporalMutation;
import com.city.model.TimeConstraint;
import com.city.service.slot.SlotOptionService;
import com.city.service.trace.AgentTraceService;
import com.city.util.LlmJsonService;
import com.city.util.SlotJsonPicker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * <p>一次识别同时产出四类信息：intent、九维 slots、ConstraintOperation、TemporalMutation。
 * 这里的 slots/operations/temporal 只表达“本轮新增或修改了什么”，不会复制完整历史状态；
 * 历史状态的真正合并由 SlotMergeService、SlotMutationService、TimeResolutionService 在 Java 层完成。</p>
 *
 * <p>LLM 负责语义理解，但不能直接制造任意槽位值：所有槽位和 operation value 都会经过
 * SlotOptionService 提供的启用字典过滤。模型调用或 JSON 结构解析失败时才进入关键词 fallback。</p>
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
            @Value("${diet.llm.main-model:qwen-max}") String modelName
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
     * @param knownSlots 已持久化的九维条件，仅作为理解上下文，不要求模型复制输出
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
        try {
            // 字典既注入 Prompt，也用于解析后的白名单过滤，形成输入提示 + 输出约束双保险。
            Map<String, List<String>> slotOptions = slotOptionService.findAllOptions();
            ReActAgent agent = agentFactory.get(sessionId).intent();

            // 历史对话由后端显式放进 Prompt；清空 Agent 自身 memory，避免出现两套上下文来源。
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "IntentAgent",
                    modelName,
                    agent,
                    buildUserPrompt(userId, sessionId, userInput, knownSlots, knownTimeConstraint, recentHistory, slotOptions)
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
     * <p>这里把当前时间、历史状态、最近对话和槽位字典都显式传给模型；同时要求输出“Patch 而非快照”，
     * 是为了让会话状态合并规则继续由 Java 控制，避免 LLM 每轮重写整份 SessionState。</p>
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
                当前已生效时间条件: %s
                可用标准标签: %s
                当前用户消息: %s

                ## 本轮输出约束
                - 输出的是当前消息带来的语义 Patch，不复制未变化的历史条件。
                - slots 必须完整包含九个数组字段：city、location、experienceGoal、companion、budget、activityType、style、duration、feature。
                - location 只表示地理区域；“近地铁/交通方便”等写 feature。
                - experienceGoal 表示用户想获得的体验，如放松/社交/解压；companion 表示同行关系。
                - duration 只表示活动自身持续时间，如 1小时内/1-2小时/2-4小时/半天/全天；室内、少排队、交通方便等绝不能写 duration。
                - feature 表示客观特征/便利性，如室内、户外、近地铁、少排队、交通方便。
                - 用户自己的可用时间只写 temporal，不写 duration。
                - operations 只能修改上述九维普通槽位；时间变化只写 temporal。
                - 纯“换一批”必须是 MEAL_ADJUST + 空 slots + operations=[] + temporal KEEP/KEEP。
                - 最终只输出合法 JSON，顶层只能包含 intent、slots、operations、temporal、confidence。
                """.formatted(
                now.toLocalDateTime(),
                now.toLocalDate(),
                now.getDayOfWeek(),
                TIME_ZONE,
                userId,
                sessionId,
                recentHistory,
                knownSlots == null ? SlotBundle.empty() : knownSlots,
                safeTime,
                slotOptions,
                userInput
        );
    }

    /** 将模型 JSON 统一转换成 IntentResult；任一关键结构异常会抛出并由 recognize() 进入 fallback。 */
    private IntentResult parseResult(String content, Map<String, List<String>> slotOptions) {
        JsonNode root = llmJsonService.parseObject(content);
        Intent intent = parseIntent(root.path("intent").asText(null));
        JsonNode slotsNode = root.path("slots").isObject() ? root.path("slots") : root;
        SlotBundle slots = parseSlots(slotsNode, slotOptions);
        double confidence = root.path("confidence").asDouble(0.5);
        return new IntentResult(
                intent,
                slots,
                confidence,
                parseOperations(root.path("operations"), slotOptions),
                parseTemporal(root.path("temporal"))
        );
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
     * 解析九维槽位，并通过 SlotJsonPicker 把每个值限制在数据库启用字典中。
     * 模型即使输出了不存在的标签，也不会进入后续 SessionState 和 SQL 检索。
     */
    private SlotBundle parseSlots(JsonNode node, Map<String, List<String>> options) {
        return new SlotBundle(
                SlotJsonPicker.pick(node, "city", options),
                SlotJsonPicker.pick(node, "location", options),
                SlotJsonPicker.pick(node, "experienceGoal", options),
                SlotJsonPicker.pick(node, "companion", options),
                SlotJsonPicker.pick(node, "budget", options),
                SlotJsonPicker.pick(node, "activityType", options),
                SlotJsonPicker.pick(node, "style", options),
                SlotJsonPicker.pick(node, "duration", options),
                SlotJsonPicker.pick(node, "feature", options)
        );
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
                fallbackSlots(userInput, options),
                0.2,
                fallbackOperations(userInput, options),
                TemporalMutation.keep(),
                true
        );
    }

    /** fallback 槽位只做“原文包含某个合法字典值”的精确命中，不进行开放式语义推断。 */
    private SlotBundle fallbackSlots(String userInput, Map<String, List<String>> options) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, List<String>> entry : options.entrySet()) {
            List<String> hits = entry.getValue().stream()
                    .filter(value -> userInput != null && userInput.contains(value))
                    .toList();
            ArrayNode values = node.putArray(entry.getKey());
            hits.forEach(values::add);
        }
        return parseSlots(node, options);
    }

    /** 模型失败时才把少量明确的“不限/不要”表达转换成结构化 operations。 */
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
                if (text.contains("不要" + value)
                        || text.contains("不想" + value)
                        || text.contains("不想看" + value)
                        || text.contains("别" + value)) {
                    result.add(new ConstraintOperation(field, ConstraintOperationType.REMOVE, List.of(value), text));
                }
            }
        }
        return List.copyOf(result);
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
     * 这里优先保证系统能继续路由，而不是追求与主模型同等的语义覆盖率。
     */
    private Intent fallbackIntent(String userInput) {
        if (userInput == null || userInput.isBlank()) return Intent.CLARIFY_NEEDED;
        if (containsAny(userInput, "危险", "偏远", "深夜独自", "违法", "未成年人进入")) return Intent.HEALTH_RISK;
        if (containsAny(userInput, "换一批", "换个", "不要户外", "室内", "便宜点", "近一点", "安静点")) return Intent.MEAL_ADJUST;
        if (containsActivityPlanSignal(userInput)) return Intent.ACTIVITY_PLAN;
        if (containsAny(userInput, "你是谁", "你是 AI", "你好")) return Intent.OTHER;
        if (containsAny(userInput,
                "去哪", "去哪里", "玩什么", "活动", "展览", "电影", "演出", "运动", "探店", "推荐",
                "半天", "一天", "一日", "全天", "一整天", "有空", "都行", "都可以")) {
            return Intent.MEAL_RECOMMENDATION;
        }
        return Intent.CLARIFY_NEEDED;
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

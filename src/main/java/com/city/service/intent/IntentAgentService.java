package com.city.service.intent;

import com.city.agent.factory.AgentFactory;
import com.city.model.ConversationTurn;
import com.city.enums.Intent;
import com.city.model.IntentResult;
import com.city.model.SlotBundle;
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
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Locale;
import com.city.enums.ConstraintOperationType;
import com.city.model.ConstraintOperation;

/**
 * IntentAgent 调用服务。
 * 负责调用 LLM 识别 intent + slots，解析 JSON，失败时关键词兜底；不直接写 SessionState。
 */
@Service
public class IntentAgentService {

    /** 按 sessionId 提供 IntentAgent 实例的工厂。 */
    private final AgentFactory agentFactory;

    /** 从 LLM 输出文本中提取 JSON 对象的工具。 */
    private final LlmJsonService llmJsonService;

    /** 槽位字典服务，校验 LLM 输出的标签是否在合法候选值内。 */
    private final SlotOptionService slotOptionService;

    /** 链路追踪服务，callAgent 内部会记录 AGENT_CALL 事件。 */
    private final AgentTraceService agentTraceService;

    /** IntentAgent 使用的轻量模型名，来自配置 diet.llm.light-model。 */
    private final String modelName;

    /** 构造器注入全部依赖。 */
    public IntentAgentService(
            AgentFactory agentFactory,
            LlmJsonService llmJsonService,
            SlotOptionService slotOptionService,
            AgentTraceService agentTraceService,
            @Value("${diet.llm.light-model:qwen-turbo}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.slotOptionService = slotOptionService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    /**
     * 调用 IntentAgent 识别本轮意图和槽位。
     * 由 Orchestrator#handleTurn 调用，返回 IntentResult 供路由和槽位合并。
     */
    public IntentResult recognize(String sessionId, Long userId, String userInput, SlotBundle knownSlots, List<ConversationTurn> recentHistory) {
        try {
            // 加载活动时间、心情、同行场景、预算和活动类型等合法候选值
            Map<String, List<String>> slotOptions = slotOptionService.findAllOptions();
            
            // 从 AgentFactory 获取当前 session 绑定的 IntentAgent ReActAgent 实例
            ReActAgent agent = agentFactory.get(sessionId).intent();
            // 清空 Agent 内存，避免上一轮对话污染本轮意图识别
            agent.getMemory().clear();
            // 调用 Agent：内部走 agentTraceService.callAgent，记录 AGENT_CALL 事件（含 input/output/latency）
            Msg response = agentTraceService.callAgent(sessionId, "IntentAgent", modelName,
                    agent, buildUserPrompt(userId, sessionId, userInput, knownSlots, recentHistory, slotOptions));
            // 解析 Agent 返回的 JSON 文本为 IntentResult（intent + slots + confidence）
            return parseResult(response.getTextContent(), userInput, slotOptions);
        } catch (Exception ignored) {
            // LLM 超时/JSON 解析失败时不抛异常，走关键词 fallback 保证 Orchestrator 可继续
            Map<String, List<String>> fallbackOptions;
            try { fallbackOptions = slotOptionService.findAllOptions(); }
            catch (Exception unavailable) { fallbackOptions = Map.of(); }
            return fallback(userInput, fallbackOptions);
        }
    }

    /** 构造传给 IntentAgent 的用户 prompt，包含上下文和输出格式约束。 */
    private String buildUserPrompt(Long userId, String sessionId, String userInput, SlotBundle knownSlots, List<ConversationTurn> recentHistory, Map<String, List<String>> slotOptions) {
        return """
                你是“城市活动推荐”的语义解析器，不推荐活动、不解释、不闲聊，只提取本轮用户意图和筛选条件变化。

                ## 输入上下文
                userId: %s
                sessionId: %s
                最近对话: %s
                当前已生效条件: %s
                可用标准标签: %s
                当前用户消息: %s

                ## 任务
                输出且只输出一个合法 JSON 对象，不使用 Markdown、代码块或额外文字。
                JSON 顶层只能包含 intent、slots、operations、confidence 四个字段。

                ## intent 枚举
                - MEAL_RECOMMENDATION：首次请求推荐、继续补充条件，或意图不明确但仍在询问活动。
                - MEAL_ADJUST：修改、追加、删除、清除条件，或明确要求“换一批”。
                - ACTIVITY_PLAN：要求半天、一天、行程或活动安排。
                - HEALTH_RISK：深夜独行、偏远地点、极端天气等安全风险。
                - OTHER：与城市活动无关。
                - CLARIFY_NEEDED：无法判断用户是否在请求活动推荐。

                ## slots 规则
                slots 只放本轮能从“可用标准标签”精确映射的正向标签；字段必须完整输出：
                city、location、mood、scene、budget、activityType、style、duration。
                未提及的字段输出 []。禁止创造标签、禁止把历史条件重复抄进 slots。
                今天、明天、后天、明确日期、周几、周末、上午/下午/晚上由后端解析；不要写入 slots。

                ## operations 规则
                operations 是“对当前已生效条件”的补丁，数组中每一项必须严格是：
                {"field":"字段名","op":"SET|ADD|REMOVE|CLEAR","values":["标准标签"],"raw":"用户原始片段"}
                values 是唯一合法的数组字段，禁止 value、items、valueList；raw 必须是字符串。
                field 只能是 city、location、mood、scene、budget、activityType、style、duration、time。
                - SET：将该字段替换为 values，例如“改成北京”“只看电影”。
                - ADD：保留旧值并加入 values，例如“电影和展览都可以”。
                - REMOVE：排除 values，例如“不要展览”“别太热闹”。
                - CLEAR：取消该字段全部限制，values 必须为 []，例如“预算不限”“城市不限”。
                - field 为 time 时只可使用 SET 或 CLEAR，values 必须为 []，原表达写入 raw。
                - 无明确变更时 operations 必须为 []。
                - 同一字段若出现 CLEAR，不能再输出该字段其他操作；若同时出现 SET 和 ADD，以 SET 为准。
                - “换一批”本身不产生 slots 或 operations，只输出 intent=MEAL_ADJUST。

                ## 通用正确输出示例
                用户：“改成北京，电影和展览都可以，不要太热闹，预算不限”
                {
                  "intent":"MEAL_ADJUST",
                  "slots":{"city":["北京"],"location":[],"mood":[],"scene":[],"budget":[],"activityType":["电影","展览"],"style":[],"duration":[]},
                  "operations":[
                    {"field":"city","op":"SET","values":["北京"],"raw":"改成北京"},
                    {"field":"activityType","op":"ADD","values":["电影","展览"],"raw":"电影和展览都可以"},
                    {"field":"style","op":"REMOVE","values":["热闹"],"raw":"不要太热闹"},
                    {"field":"budget","op":"CLEAR","values":[],"raw":"预算不限"}
                  ],
                  "confidence":0.92
                }
                """.formatted(userId, sessionId, recentHistory, knownSlots, slotOptions, userInput);
    }

    /** 将 Agent 返回的 JSON 文本解析为 IntentResult。 */
    private IntentResult parseResult(String content, String userInput, Map<String, List<String>> slotOptions) {
        // 从 LLM 输出中提取 JSON 根节点（可能包裹在 markdown 代码块中）
        JsonNode root = llmJsonService.parseObject(content);

        // 读取 intent 字段并解析为 Intent 枚举，失败时走关键词兜底
        Intent intent = parseIntent(root.path("intent").asText(null), userInput);

        // 若 slots 是嵌套对象则取 slots 节点，否则直接用 root（兼容扁平 JSON）
        JsonNode slotsNode = root.path("slots").isObject() ? root.path("slots") : root;

        // 将 JSON slots 各字段映射为 SlotBundle，并过滤非法字典值
        SlotBundle slots = parseSlots(slotsNode, slotOptions);

        // 读取 confidence 字段，缺省 0.5
        double confidence = root.path("confidence").asDouble(0.5);

        // 组装并返回 IntentResult
        return new IntentResult(intent, slots, confidence, parseOperations(root.path("operations"), slotOptions));
    }

    /** 将 JSON 中的 intent 字符串解析为 Intent 枚举。 */
    private Intent parseIntent(String rawIntent, String userInput) {
        try {
            // rawIntent 为 null 时走关键词兜底；否则 Intent.valueOf 解析
            return rawIntent == null ? fallbackIntent(userInput) : Intent.valueOf(rawIntent);
        } catch (Exception ignored) {
            // 非法枚举名时走关键词兜底
            return fallbackIntent(userInput);
        }
    }

    /** 将 JSON slots 节点各字段转为 SlotBundle，通过 SlotJsonPicker 过滤非法标签。 */
    private SlotBundle parseSlots(JsonNode node, Map<String, List<String>> options) {
        return new SlotBundle(
                SlotJsonPicker.pick(node, "city", options),              // 城市标签
                SlotJsonPicker.pick(node, "location", options),          // 位置/区域标签
                SlotJsonPicker.pick(node, "mood", options),          // 活动状态标签
                SlotJsonPicker.pick(node, "scene", options),         // 同行人标签
                SlotJsonPicker.pick(node, "budget", options),    // 预算标签
                SlotJsonPicker.pick(node, "activityType", options),       // 活动类型标签
                SlotJsonPicker.pick(node, "style", options),         // 活动风格标签
                SlotJsonPicker.pick(node, "duration", options)    // 活动时长标签
        );
    }

    private List<ConstraintOperation> parseOperations(JsonNode node, Map<String, List<String>> options) {
        if (!node.isArray()) return List.of();
        List<ConstraintOperation> result = new ArrayList<>();
        for (JsonNode item : node) {
            String field = item.path("field").asText("").trim();
            String opText = item.path("op").asText("").trim();
            if (!(SlotOptionService.SLOT_NAMES.contains(field) || "time".equals(field))) continue;
            try {
                ConstraintOperationType op = ConstraintOperationType.valueOf(opText.toUpperCase(Locale.ROOT));
                List<String> values = "time".equals(field) ? List.of() : SlotJsonPicker.pick(item, "values", optionsFor(field, options));
                result.add(new ConstraintOperation(field, op, values, item.path("raw").asText("")));
            } catch (IllegalArgumentException ignored) {
                // 单个操作无效不影响本轮其他语义。
            }
        }
        return List.copyOf(result);
    }

    private Map<String, List<String>> optionsFor(String field, Map<String, List<String>> options) {
        return Map.of("values", options.getOrDefault(field, List.of()));
    }

    /** LLM 完全失败时的保守兜底 IntentResult，confidence 固定 0.2。 */
    private IntentResult fallback(String userInput, Map<String, List<String>> options) {
        return new IntentResult(
                fallbackIntent(userInput),                                                          // 关键词推断意图
                fallbackSlots(userInput, options),                                                   // 基于字典提取多值槽位
                0.2                                                                                 // 低置信度
        );
    }

    private SlotBundle fallbackSlots(String userInput, Map<String, List<String>> options) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, List<String>> entry : options.entrySet()) {
            List<String> hits = entry.getValue().stream().filter(value -> userInput != null && userInput.contains(value)).toList();
            ArrayNode values = node.putArray(entry.getKey());
            hits.forEach(values::add);
        }
        return parseSlots(node, options);
    }

    /** 关键词规则推断意图，按优先级依次匹配。 */
    private Intent fallbackIntent(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Intent.CLARIFY_NEEDED;  // 空输入 → 需要澄清
        }
        if (containsAny(userInput, "危险", "偏远", "深夜独自", "违法", "未成年人进入")) {
            return Intent.HEALTH_RISK;     // 活动安全风险关键词，复用原枚举分支
        }
        if (containsAny(userInput, "换一批", "换个", "不要户外", "室内", "便宜点", "近一点", "安静点")) {
            return Intent.MEAL_ADJUST;   // 调整推荐关键词
        }
        if (containsAny(userInput, "半天", "一天", "行程", "安排一下")) {
            return Intent.ACTIVITY_PLAN;     // 多餐规划关键词
        }
        if (containsAny(userInput, "你是谁", "你是 AI", "你好")) {
            return Intent.OTHER;      // 与饮食无关等关键词
        }
        if (containsAny(userInput, "去哪", "去哪里", "玩什么", "活动", "展览", "电影", "演出", "推荐")) {
            return Intent.MEAL_RECOMMENDATION; // 推荐关键词
        }
        return Intent.CLARIFY_NEEDED;    // 默认 → 需要澄清
    }

    /** 判断 text 是否包含 keywords 中任一子串。 */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

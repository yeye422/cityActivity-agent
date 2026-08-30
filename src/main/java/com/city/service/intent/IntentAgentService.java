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
 * IntentAgent 调用服务。
 * 负责调用 LLM 识别 intent + slots + temporal，解析 JSON，失败时关键词兜底；不直接写 SessionState。
 */
@Service
public class IntentAgentService {

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
            @Value("${diet.llm.light-model:qwen-turbo}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.llmJsonService = llmJsonService;
        this.slotOptionService = slotOptionService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    public IntentResult recognize(String sessionId, Long userId, String userInput, SlotBundle knownSlots, List<ConversationTurn> recentHistory) {
        try {
            Map<String, List<String>> slotOptions = slotOptionService.findAllOptions();
            ReActAgent agent = agentFactory.get(sessionId).intent();
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(sessionId, "IntentAgent", modelName,
                    agent, buildUserPrompt(userId, sessionId, userInput, knownSlots, recentHistory, slotOptions));
            return parseResult(response.getTextContent(), userInput, slotOptions);
        } catch (Exception ignored) {
            Map<String, List<String>> fallbackOptions;
            try { fallbackOptions = slotOptionService.findAllOptions(); }
            catch (Exception unavailable) { fallbackOptions = Map.of(); }
            return fallback(userInput, fallbackOptions);
        }
    }

    private String buildUserPrompt(Long userId, String sessionId, String userInput, SlotBundle knownSlots,
                                   List<ConversationTurn> recentHistory, Map<String, List<String>> slotOptions) {
        ZonedDateTime now = ZonedDateTime.now(TIME_ZONE);
        return """
                你是“城市活动推荐”的语义解析器，不推荐活动、不解释、不闲聊，只提取本轮用户意图和筛选条件变化。

                ## 当前时间上下文
                currentDateTime: %s
                currentDate: %s
                currentDayOfWeek: %s
                timezone: %s
                注意：所有“今天/明天/后天/本周/下周/下个月”等相对日期，都必须基于这里的 currentDate 计算。
                一周按周一到周日定义；“下周六”表示下一自然周里的星期六。

                ## 输入上下文
                userId: %s
                sessionId: %s
                最近对话: %s
                当前已生效普通条件: %s
                可用标准标签: %s
                当前用户消息: %s

                ## 任务
                输出且只输出一个合法 JSON 对象，不使用 Markdown、代码块或额外文字。
                JSON 顶层只能包含 intent、slots、operations、temporal、confidence 五个字段。

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
                未提及字段输出 []。禁止创造标签、禁止把历史条件重复抄进 slots。
                时间绝不能写入 slots。

                ## operations 规则
                operations 只处理普通属性槽位，数组中每项必须是：
                {"field":"字段名","op":"SET|ADD|REMOVE|CLEAR","values":["标准标签"],"raw":"用户原始片段"}
                field 只能是 city、location、mood、scene、budget、activityType、style、duration。
                - SET：替换该字段。
                - ADD：保留旧值并加入 values。
                - REMOVE：排除 values。
                - CLEAR：取消该字段全部限制，values 必须为 []。
                - 无明确普通槽位变更时 operations=[]。
                - 时间变更不要写进 operations，统一写 temporal。

                ## temporal 规则
                temporal 必须完整输出以下字段：
                {"raw":"","dateMode":"KEEP|SET|CLEAR","dateStart":null,"dateEnd":null,
                 "timeMode":"KEEP|SET|CLEAR","timeStart":null,"timeEnd":null,
                 "approximate":false,"confidence":0.0}

                temporal 表示“本轮对历史时间条件的修改”，不是历史时间的完整抄写：
                - KEEP：本轮没有修改该维度，值必须为 null。
                - SET：本轮明确设置/修改该维度，必须输出绝对日期或绝对时间范围。
                - CLEAR：本轮明确取消该维度限制，值必须为 null。
                - 用户完全没提时间：dateMode=KEEP，timeMode=KEEP，raw=""。
                - “改晚上”：dateMode=KEEP，timeMode=SET。
                - “改周日”：dateMode=SET，timeMode=KEEP。
                - “几点都行”：dateMode=KEEP，timeMode=CLEAR。
                - “哪天都行”：dateMode=CLEAR，timeMode=KEEP。
                - “时间不限/随时都行”：dateMode=CLEAR，timeMode=CLEAR。
                - 日期格式固定 yyyy-MM-dd；时间格式固定 HH:mm。
                - 单个明确时刻，例如“下午3点”，输出一个 1 小时时间窗，如 15:00~16:00。
                - “上午”=08:00~12:00，“下午”=12:00~18:00，“晚上”=18:00~23:00，“凌晨”=00:00~05:00。
                - “三点左右/大概三点”统一按 ±1 小时展开，并设置 approximate=true，例如下午3点左右 => 14:00~16:00。
                - 不要猜测复杂 OR、排除时间、周期时间或跨午夜范围；无法可靠解析时对应维度使用 KEEP，并降低 temporal.confidence。

                ## 时间示例
                如果 currentDate=2026-08-30（SUNDAY）：
                用户：“下周六下午三点左右”
                temporal={"raw":"下周六下午三点左右","dateMode":"SET","dateStart":"2026-09-05","dateEnd":"2026-09-05","timeMode":"SET","timeStart":"14:00","timeEnd":"16:00","approximate":true,"confidence":0.96}

                用户：“下个月5号”
                temporal={"raw":"下个月5号","dateMode":"SET","dateStart":"2026-09-05","dateEnd":"2026-09-05","timeMode":"KEEP","timeStart":null,"timeEnd":null,"approximate":false,"confidence":0.96}

                用户：“改成晚上”
                temporal={"raw":"晚上","dateMode":"KEEP","dateStart":null,"dateEnd":null,"timeMode":"SET","timeStart":"18:00","timeEnd":"23:00","approximate":false,"confidence":0.95}

                ## 普通槽位示例
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
                  "temporal":{"raw":"","dateMode":"KEEP","dateStart":null,"dateEnd":null,"timeMode":"KEEP","timeStart":null,"timeEnd":null,"approximate":false,"confidence":1.0},
                  "confidence":0.92
                }
                """.formatted(
                now.toLocalDateTime(), now.toLocalDate(), now.getDayOfWeek(), TIME_ZONE,
                userId, sessionId, recentHistory, knownSlots, slotOptions, userInput);
    }

    private IntentResult parseResult(String content, String userInput, Map<String, List<String>> slotOptions) {
        JsonNode root = llmJsonService.parseObject(content);
        Intent intent = parseIntent(root.path("intent").asText(null), userInput);
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

    private TemporalMutation parseTemporal(JsonNode node) {
        if (node == null || !node.isObject()) return TemporalMutation.keep();
        try {
            TemporalMode dateMode = TemporalMode.valueOf(node.path("dateMode").asText("KEEP").toUpperCase(Locale.ROOT));
            TemporalMode timeMode = TemporalMode.valueOf(node.path("timeMode").asText("KEEP").toUpperCase(Locale.ROOT));
            LocalDate dateStart = parseDate(node.path("dateStart"));
            LocalDate dateEnd = parseDate(node.path("dateEnd"));
            LocalTime timeStart = parseTime(node.path("timeStart"));
            LocalTime timeEnd = parseTime(node.path("timeEnd"));
            return new TemporalMutation(
                    node.path("raw").asText(""),
                    dateMode,
                    dateStart,
                    dateEnd,
                    timeMode,
                    timeStart,
                    timeEnd,
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

    private Intent parseIntent(String rawIntent, String userInput) {
        try {
            return rawIntent == null ? fallbackIntent(userInput) : Intent.valueOf(rawIntent);
        } catch (Exception ignored) {
            return fallbackIntent(userInput);
        }
    }

    private SlotBundle parseSlots(JsonNode node, Map<String, List<String>> options) {
        return new SlotBundle(
                SlotJsonPicker.pick(node, "city", options),
                SlotJsonPicker.pick(node, "location", options),
                SlotJsonPicker.pick(node, "mood", options),
                SlotJsonPicker.pick(node, "scene", options),
                SlotJsonPicker.pick(node, "budget", options),
                SlotJsonPicker.pick(node, "activityType", options),
                SlotJsonPicker.pick(node, "style", options),
                SlotJsonPicker.pick(node, "duration", options)
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

    private IntentResult fallback(String userInput, Map<String, List<String>> options) {
        return new IntentResult(fallbackIntent(userInput), fallbackSlots(userInput, options), 0.2);
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

    private Intent fallbackIntent(String userInput) {
        if (userInput == null || userInput.isBlank()) return Intent.CLARIFY_NEEDED;
        if (containsAny(userInput, "危险", "偏远", "深夜独自", "违法", "未成年人进入")) return Intent.HEALTH_RISK;
        if (containsAny(userInput, "换一批", "换个", "不要户外", "室内", "便宜点", "近一点", "安静点")) return Intent.MEAL_ADJUST;
        if (containsAny(userInput, "半天", "一天", "行程", "安排一下")) return Intent.ACTIVITY_PLAN;
        if (containsAny(userInput, "你是谁", "你是 AI", "你好")) return Intent.OTHER;
        if (containsAny(userInput, "去哪", "去哪里", "玩什么", "活动", "展览", "电影", "演出", "推荐")) return Intent.MEAL_RECOMMENDATION;
        return Intent.CLARIFY_NEEDED;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}

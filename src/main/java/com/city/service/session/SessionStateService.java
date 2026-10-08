package com.city.service.session;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.mapper.SessionMapper;
import com.city.model.RelaxationContext;
import com.city.model.SessionRow;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SessionState 的持久化边界。
 *
 * <p>Orchestrator 只操作结构化 SessionState，本服务负责在 SessionState 与 city_sessions 数据库行之间转换。
 * 其中九维槽位、排除槽位、时间条件、澄清状态、放宽推荐上下文等扩展状态统一编码在 slots JSON 中，
 * 而 phase、lastRecommendedActivityIds 等兼容已有表结构的字段继续使用独立列。</p>
 *
 * <p>这样做的核心目的，是让多轮对话状态成为后端可恢复的持久化事实，而不是依赖 Agent 内存。</p>
 */
@Service
public class SessionStateService {

    /** Jackson 反序列化 List<Long> 时使用，主要用于历史推荐活动 ID。 */
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {};

    /** Jackson 反序列化 List<String> 时使用，主要用于槽位和 unconstrainedSlots。 */
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final SessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    public SessionStateService(SessionMapper sessionMapper, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
    }

    /** 创建全新的会话状态并立即落库，返回后即可进入 Orchestrator 主流程。 */
    public SessionState create(Long userId, SourceMode sourceMode) {
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "");
        SessionState state = SessionState.fresh(sessionId, userId, sourceMode);
        insert(state);
        return state;
    }

    /**
     * 主聊天入口使用的“读或建”语义。
     *
     * <p>没有 sessionId 时生成新 ID；客户端携带了 sessionId 但数据库尚无记录时，
     * 仍按该 ID 初始化状态，保证前端可以先创建会话标识再发首轮请求。</p>
     */
    public SessionState loadOrCreate(String sessionId, Long userId, SourceMode sourceMode) {
        if (sessionId == null || sessionId.isBlank()) {
            return create(userId, sourceMode);
        }
        SessionRow row = sessionMapper.findById(sessionId, userId);
        if (row == null) {
            SessionState state = SessionState.fresh(sessionId, userId, sourceMode);
            insert(state);
            return state;
        }
        return fromRow(row, sourceMode);
    }

    /**
     * 读取已经存在的会话，不允许因为请求参数缺失而创建新会话。
     * 主要用于“点击放宽方案”这类二阶段操作，执行上下文必须以后端已保存状态为准。
     */
    public SessionState loadExisting(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new CityException("会话 ID 不能为空");
        }
        SessionRow row = sessionMapper.findById(sessionId, userId);
        if (row == null) {
            throw new CityException("会话不存在或已失效，请重新发起推荐");
        }
        // requestSourceMode=null：二阶段操作不能用客户端参数覆盖后端已持久化的来源模式。
        return fromRow(row, null);
    }

    /**
     * 将完整 SessionState 覆盖保存到数据库。
     * update=0 通常意味着会话不存在或 userId 不匹配，因此视为状态保存失败而不是静默忽略。
     */
    public void save(SessionState state) {
        SessionRow row = toRow(state);
        int updated = sessionMapper.update(row);
        if (updated == 0) {
            throw new CityException("会话状态保存失败");
        }
    }

    /** 首次创建会话时写入 city_sessions。 */
    private void insert(SessionState state) {
        sessionMapper.insert(toRow(state));
    }

    /**
     * 数据库行 → 领域状态。
     *
     * <p>读取时同时兼容旧会话：缺失的新字段统一回落为空对象/空集合，避免状态结构升级后历史 Session 无法恢复。</p>
     */
    private SessionState fromRow(SessionRow row, SourceMode requestSourceMode) {
        try {
            JsonNode root = parseObject(row.getSlots());
            JsonNode meta = root.path("_meta");
            Intent currentIntent = parseIntent(meta.path("currentIntent").asText(null));

            // 九维正向槽位保存在 slots JSON 顶层，字段缺失时 readStringList 返回空列表。
            SlotBundle slots = new SlotBundle(
                    readStringList(root, "city"),
                    readStringList(root, "location"),
                    readStringList(root, "experienceGoal"),
                    readStringList(root, "companion"),
                    readStringList(root, "budget"),
                    readStringList(root, "activityType"),
                    readStringList(root, "style"),
                    readStringList(root, "duration"),
                    readStringList(root, "feature")
            );

            // excludedSlots 与 unconstrainedSlots 分别表示“明确不要”和“明确不限”，不能与普通空槽位混为一谈。
            SlotBundle excludedSlots = root.path("excludedSlots").isObject()
                    ? objectMapper.treeToValue(root.path("excludedSlots"), SlotBundle.class)
                    : SlotBundle.empty();
            Set<String> unconstrainedSlots = root.path("unconstrainedSlots").isArray()
                    ? new LinkedHashSet<>(objectMapper.readValue(root.path("unconstrainedSlots").toString(), STRING_LIST))
                    : Set.of();

            TimeConstraint timeConstraint = root.path("timeConstraint").isObject()
                    ? objectMapper.treeToValue(root.path("timeConstraint"), TimeConstraint.class)
                    : TimeConstraint.empty();
            ClarifyField pendingClarifyField = ClarifyField.parse(root.path("pendingClarifyField").asText(null));
            RelaxationContext pendingRelaxationContext = root.path("pendingRelaxationContext").isObject()
                    ? objectMapper.treeToValue(root.path("pendingRelaxationContext"), RelaxationContext.class)
                    : null;
            String recommendationQueryKey = meta.path("recommendationQueryKey").asText("");

            // SourceMode 优先级：本轮显式请求 > 待执行放宽方案保存的来源 > 会话持久化来源 > PUBLIC 默认值。
            SourceMode persistedSourceMode = parseSourceMode(meta.path("sourceMode").asText(null));
            SourceMode effectiveSourceMode = requestSourceMode != null
                    ? requestSourceMode
                    : pendingRelaxationContext != null && pendingRelaxationContext.sourceMode() != null
                    ? pendingRelaxationContext.sourceMode()
                    : persistedSourceMode != null ? persistedSourceMode : SourceMode.PUBLIC;

            return new SessionState(
                    row.getId(),
                    row.getUserId(),
                    parsePhase(row.getPhase()),
                    effectiveSourceMode,
                    currentIntent,
                    slots,
                    excludedSlots,
                    unconstrainedSlots,
                    timeConstraint,
                    pendingClarifyField,
                    recommendationQueryKey,
                    pendingRelaxationContext,
                    parseLongList(row.getLastRecommendedActivityIds()),
                    readStringList(root, "userGoals")
            );
        } catch (Exception e) {
            throw new CityException("会话状态解析失败", e);
        }
    }

    /** 领域状态 → 数据库行；复杂扩展状态由 toSlotsJson 统一序列化。 */
    private SessionRow toRow(SessionState state) {
        SessionRow row = new SessionRow();
        row.setId(state.sessionId());
        row.setUserId(state.userId());
        row.setPhase(state.phase().name());
        row.setSlots(toSlotsJson(state));
        row.setLastRecommendedActivityIds(toJson(state.lastRecommendedActivityIds()));
        return row;
    }

    /**
     * 将多轮推荐所需的扩展状态编码进 slots JSON。
     *
     * <p>顶层保存可参与推荐查询的状态；_meta 保存路由/版本性质的辅助状态，避免和九维槽位同名。</p>
     */
    private String toSlotsJson(SessionState state) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("city", objectMapper.valueToTree(state.slots().city()));
        root.set("location", objectMapper.valueToTree(state.slots().location()));
        root.set("experienceGoal", objectMapper.valueToTree(state.slots().experienceGoal()));
        root.set("companion", objectMapper.valueToTree(state.slots().companion()));
        root.set("budget", objectMapper.valueToTree(state.slots().budget()));
        root.set("activityType", objectMapper.valueToTree(state.slots().activityType()));
        root.set("style", objectMapper.valueToTree(state.slots().style()));
        root.set("duration", objectMapper.valueToTree(state.slots().duration()));
        root.set("feature", objectMapper.valueToTree(state.slots().feature()));
        root.set("userGoals", objectMapper.valueToTree(
                state.userGoals() == null ? List.of() : state.userGoals()));
        root.set("excludedSlots", objectMapper.valueToTree(
                state.excludedSlots() == null ? SlotBundle.empty() : state.excludedSlots()));
        root.set("unconstrainedSlots", objectMapper.valueToTree(
                state.unconstrainedSlots() == null ? Set.of() : state.unconstrainedSlots()));
        root.set("timeConstraint", objectMapper.valueToTree(
                state.timeConstraint() == null ? TimeConstraint.empty() : state.timeConstraint()));
        if (state.pendingClarifyField() == null) {
            root.putNull("pendingClarifyField");
        } else {
            root.put("pendingClarifyField", state.pendingClarifyField().name());
        }
        root.set("pendingRelaxationContext", objectMapper.valueToTree(state.pendingRelaxationContext()));

        // _meta 中的值影响编排行为，但不是用户偏好槽位，因此单独分组保存。
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("currentIntent", state.currentIntent() == null ? null : state.currentIntent().name());
        meta.put("recommendationQueryKey", state.recommendationQueryKey());
        meta.put("sourceMode", state.sourceMode() == null ? null : state.sourceMode().name());
        root.set("_meta", meta);
        return root.toString();
    }

    /** 空 JSON 按空对象处理，兼容早期会话和异常初始化数据。 */
    private JsonNode parseObject(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(json);
    }

    /** 从 slots JSON 读取字符串数组；字段不存在或类型不正确时按空槽位处理。 */
    private List<String> readStringList(JsonNode root, String field) throws Exception {
        JsonNode node = root.path(field);
        if (!node.isArray()) {
            return List.of();
        }
        return objectMapper.readValue(node.toString(), STRING_LIST);
    }

    /** 解析历史推荐 ID；空值代表当前没有可供 ADJUST/换批使用的上一轮结果。 */
    private List<Long> parseLongList(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, LONG_LIST);
    }

    /** 通用 JSON 序列化；空值统一写成 []，保持数据库字段结构稳定。 */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new CityException("会话状态 JSON 序列化失败", e);
        }
    }

    /** phase 脏值回退 START，保证历史异常数据不会阻断新一轮对话。 */
    private SessionPhase parsePhase(String phase) {
        try {
            return phase == null ? SessionPhase.START : SessionPhase.valueOf(phase);
        } catch (Exception ignored) {
            return SessionPhase.START;
        }
    }

    /** intent 脏值回退 null，由后续 Orchestrator 按本轮识别结果继续执行。 */
    private Intent parseIntent(String intent) {
        try {
            return intent == null || intent.isBlank() ? null : Intent.valueOf(intent);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** SourceMode 脏值回退 null，最终由 fromRow 的来源优先级规则确定有效值。 */
    private SourceMode parseSourceMode(String sourceMode) {
        try {
            return sourceMode == null || sourceMode.isBlank() ? null : SourceMode.valueOf(sourceMode);
        } catch (Exception ignored) {
            return null;
        }
    }
}

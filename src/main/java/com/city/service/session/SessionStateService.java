package com.city.service.session;

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

/** 会话状态读写服务。 */
@Service
public class SessionStateService {

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final SessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    public SessionStateService(SessionMapper sessionMapper, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.objectMapper = objectMapper;
    }

    public SessionState create(Long userId, SourceMode sourceMode) {
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "");
        SessionState state = SessionState.fresh(sessionId, userId, sourceMode);
        insert(state);
        return state;
    }

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
        return fromRow(row, null);
    }

    public void save(SessionState state) {
        SessionRow row = toRow(state);
        int updated = sessionMapper.update(row);
        if (updated == 0) {
            throw new CityException("会话状态保存失败");
        }
    }

    private void insert(SessionState state) {
        sessionMapper.insert(toRow(state));
    }

    private SessionState fromRow(SessionRow row, SourceMode requestSourceMode) {
        try {
            JsonNode root = parseObject(row.getSlots());
            JsonNode meta = root.path("_meta");
            Intent currentIntent = parseIntent(meta.path("currentIntent").asText(null));
            SlotBundle slots = new SlotBundle(
                    readStringList(root, "city"),
                    readStringList(root, "location"),
                    readStringListWithLegacy(root, "experienceGoal", "mood"),
                    readStringListWithLegacy(root, "companion", "scene"),
                    readStringList(root, "budget"),
                    readStringList(root, "activityType"),
                    readStringList(root, "style"),
                    readStringList(root, "duration"),
                    readStringList(root, "feature")
            );
            SlotBundle excludedSlots = root.path("excludedSlots").isObject()
                    ? objectMapper.treeToValue(root.path("excludedSlots"), SlotBundle.class)
                    : SlotBundle.empty();
            Set<String> unconstrainedSlots = root.path("unconstrainedSlots").isArray()
                    ? new LinkedHashSet<>(objectMapper.readValue(root.path("unconstrainedSlots").toString(), STRING_LIST))
                    : Set.of();
            TimeConstraint timeConstraint = root.path("timeConstraint").isObject()
                    ? objectMapper.treeToValue(root.path("timeConstraint"), TimeConstraint.class)
                    : TimeConstraint.empty();
            RelaxationContext pendingRelaxationContext = root.path("pendingRelaxationContext").isObject()
                    ? objectMapper.treeToValue(root.path("pendingRelaxationContext"), RelaxationContext.class)
                    : null;
            String recommendationQueryKey = meta.path("recommendationQueryKey").asText("");
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
                    recommendationQueryKey,
                    pendingRelaxationContext,
                    parseLongList(row.getLastRecommendedActivityIds())
            );
        } catch (Exception e) {
            throw new CityException("会话状态解析失败", e);
        }
    }

    private SessionRow toRow(SessionState state) {
        SessionRow row = new SessionRow();
        row.setId(state.sessionId());
        row.setUserId(state.userId());
        row.setPhase(state.phase().name());
        row.setSlots(toSlotsJson(state));
        row.setLastRecommendedActivityIds(toJson(state.lastRecommendedActivityIds()));
        return row;
    }

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
        root.set("excludedSlots", objectMapper.valueToTree(
                state.excludedSlots() == null ? SlotBundle.empty() : state.excludedSlots()));
        root.set("unconstrainedSlots", objectMapper.valueToTree(
                state.unconstrainedSlots() == null ? Set.of() : state.unconstrainedSlots()));
        root.set("timeConstraint", objectMapper.valueToTree(
                state.timeConstraint() == null ? TimeConstraint.empty() : state.timeConstraint()));
        root.set("pendingRelaxationContext", objectMapper.valueToTree(state.pendingRelaxationContext()));
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("currentIntent", state.currentIntent() == null ? null : state.currentIntent().name());
        meta.put("recommendationQueryKey", state.recommendationQueryKey());
        meta.put("sourceMode", state.sourceMode() == null ? null : state.sourceMode().name());
        root.set("_meta", meta);
        return root.toString();
    }

    private JsonNode parseObject(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(json);
    }

    private List<String> readStringList(JsonNode root, String field) throws Exception {
        JsonNode node = root.path(field);
        if (!node.isArray()) {
            return List.of();
        }
        return objectMapper.readValue(node.toString(), STRING_LIST);
    }

    /** 兼容旧会话 JSON 中 mood/scene 字段；一旦保存会统一写成新九维字段名。 */
    private List<String> readStringListWithLegacy(JsonNode root, String field, String legacyField) throws Exception {
        List<String> current = readStringList(root, field);
        return current.isEmpty() ? readStringList(root, legacyField) : current;
    }

    private List<Long> parseLongList(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, LONG_LIST);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new CityException("会话状态 JSON 序列化失败", e);
        }
    }

    private SessionPhase parsePhase(String phase) {
        try {
            return phase == null ? SessionPhase.START : SessionPhase.valueOf(phase);
        } catch (Exception ignored) {
            return SessionPhase.START;
        }
    }

    private Intent parseIntent(String intent) {
        try {
            return intent == null || intent.isBlank() ? null : Intent.valueOf(intent);
        } catch (Exception ignored) {
            return null;
        }
    }

    private SourceMode parseSourceMode(String sourceMode) {
        try {
            return sourceMode == null || sourceMode.isBlank() ? null : SourceMode.valueOf(sourceMode);
        } catch (Exception ignored) {
            return null;
        }
    }
}

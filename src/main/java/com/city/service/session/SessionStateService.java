package com.city.service.session;

import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.mapper.SessionMapper;
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
            SourceMode sourceMode = parseSourceMode(meta.path("sourceMode").asText(null), requestSourceMode);
            Intent currentIntent = parseIntent(meta.path("currentIntent").asText(null));
            SlotBundle slots = new SlotBundle(
                    readStringList(root, "city"),
                    readStringList(root, "location"),
                    readStringList(root, "mood"),
                    readStringList(root, "scene"),
                    readStringList(root, "budget"),
                    readStringList(root, "activityType"),
                    readStringList(root, "style"),
                    readStringList(root, "duration")
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
            String recommendationQueryKey = meta.path("recommendationQueryKey").asText("");
            return new SessionState(
                    row.getId(),
                    row.getUserId(),
                    parsePhase(row.getPhase()),
                    sourceMode,
                    currentIntent,
                    slots,
                    excludedSlots,
                    unconstrainedSlots,
                    timeConstraint,
                    recommendationQueryKey,
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
        root.set("mood", objectMapper.valueToTree(state.slots().mood()));
        root.set("scene", objectMapper.valueToTree(state.slots().scene()));
        root.set("budget", objectMapper.valueToTree(state.slots().budget()));
        root.set("activityType", objectMapper.valueToTree(state.slots().activityType()));
        root.set("style", objectMapper.valueToTree(state.slots().style()));
        root.set("duration", objectMapper.valueToTree(state.slots().duration()));
        root.set("excludedSlots", objectMapper.valueToTree(
                state.excludedSlots() == null ? SlotBundle.empty() : state.excludedSlots()));
        root.set("unconstrainedSlots", objectMapper.valueToTree(
                state.unconstrainedSlots() == null ? Set.of() : state.unconstrainedSlots()));
        root.set("timeConstraint", objectMapper.valueToTree(
                state.timeConstraint() == null ? TimeConstraint.empty() : state.timeConstraint()));
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("sourceMode", state.sourceMode() == null ? null : state.sourceMode().name());
        meta.put("currentIntent", state.currentIntent() == null ? null : state.currentIntent().name());
        meta.put("recommendationQueryKey", state.recommendationQueryKey());
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

    private SourceMode parseSourceMode(String savedSourceMode, SourceMode requestSourceMode) {
        try {
            return savedSourceMode == null || savedSourceMode.isBlank()
                    ? requestSourceMode
                    : SourceMode.valueOf(savedSourceMode);
        } catch (Exception ignored) {
            return requestSourceMode;
        }
    }
}

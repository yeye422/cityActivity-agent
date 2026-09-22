package com.city.service.session;

import com.city.enums.Intent;
import com.city.mapper.SessionMapper;
import com.city.model.ConversationTurn;
import com.city.model.SessionMessageRow;
import com.city.model.SessionRow;
import com.city.util.JsonService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 会话消息落库服务。
 * 负责会话创建和消息追加；会话状态（slots/phase）由 SessionStateService 管理。
 */
@Service
public class SessionService {

    /** MyBatis Mapper，操作会话和消息表。 */
    private final SessionMapper sessionMapper;

    /** JSON 序列化工具。 */
    private final JsonService jsonService;

    /** 注入 IntentAgent 的最近对话条数上限。 */
    private final int maxHistoryTurns;

    /** Agent 边界上传递的历史摘要字符预算。 */
    private final int maxHistoryChars;

    /** 构造器注入依赖。 */
    @Autowired
    public SessionService(
            SessionMapper sessionMapper,
            JsonService jsonService,
            @Value("${city.session.max-history-turns:10}") int maxHistoryTurns,
            @Value("${city.session.max-history-chars:1200}") int maxHistoryChars
    ) {
        this.sessionMapper = sessionMapper;
        this.jsonService = jsonService;
        this.maxHistoryTurns = maxHistoryTurns;
        this.maxHistoryChars = Math.max(120, maxHistoryChars);
    }

    /** 保留旧的纯单测构造入口。 */
    public SessionService(SessionMapper sessionMapper, JsonService jsonService, int maxHistoryTurns) {
        this(sessionMapper, jsonService, maxHistoryTurns, 1200);
    }

    /** 创建新会话并返回 sessionId（旧接口，Orchestrator 优先走 SessionStateService）。 */
    public String createSession(Long userId) {
        SessionRow row = new SessionRow();
        row.setId("sess_" + UUID.randomUUID().toString().replace("-", "")); // 生成 sessionId
        row.setUserId(userId);                                               // 绑定用户
        row.setPhase("START");                                               // 初始阶段
        row.setSlots("{}");                                                  // 空 slots JSON
        row.setLastRecommendedActivityIds(jsonService.toJsonArray(List.of()));      // 空推荐 ID 列表
        sessionMapper.insert(row);                                           // INSERT city_sessions
        return row.getId();                                                  // 返回 sessionId
    }

    /** 确保 sessionId 对应行存在，不存在则插入空会话。 */
    public void ensureSession(String sessionId, Long userId) {
        if (sessionMapper.findById(sessionId, userId) == null) {
            SessionRow row = new SessionRow();
            row.setId(sessionId);
            row.setUserId(userId);
            row.setPhase("START");
            row.setSlots("{}");
            row.setLastRecommendedActivityIds(jsonService.toJsonArray(List.of()));
            sessionMapper.insert(row);
        }
    }

    /**
     * 追加一条对话消息到 city_messages 表。
     * 由 Orchestrator 在每轮用户/助手消息产生时调用。
     */
    public void appendMessage(String sessionId, String role, String content, String intent, String traceId) {
        // INSERT：sessionId + role(user/assistant) + content + intent + traceId
        sessionMapper.insertMessage(sessionId, role, content == null ? "" : content, intent, traceId);
    }

    /**
     * 读取最近 n 条“历史”消息并转为 IntentAgent 使用的短期上下文。
     *
     * <p>Orchestrator 会先把本轮 user message 落库，再调用本方法。此时数据库最新一条通常正是
     * 当前正在处理的用户输入，而 IntentAgent 又会通过 current user message 单独接收同一文本。
     * 因此这里会识别并排除这条尚未产生 assistant 回复的最新 user message，避免当前输入在 Prompt
     * 中同时出现在 recentHistory 和 current user message 两处。</p>
     */
    public List<ConversationTurn> recentConversationTurns(String sessionId, Long userId, int n) {
        if (sessionId == null || sessionId.isBlank() || userId == null || n <= 0) {
            return List.of();
        }
        int limit = Math.min(n, Math.max(1, maxHistoryTurns));
        // 多取 1 条：如果第 1 条是本轮刚落库的 user message，排除后仍能保留完整的 n 条历史。
        List<SessionMessageRow> rows = new ArrayList<>(
                sessionMapper.listRecentMessages(sessionId, userId, limit + 1));
        if (!rows.isEmpty() && isCurrentPendingUserMessage(rows.getFirst())) {
            rows.removeFirst();
        }
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        // 只在送入 Agent 的边界压缩，不改写数据库原始消息；从最新向前填充预算。
        List<SessionMessageRow> budgeted = retainNewestWithinBudget(rows);
        Collections.reverse(budgeted); // SQL 倒序取最近消息，prompt 中按时间正序注入。
        return budgeted.stream()
                .map(this::toConversationTurn)
                .toList();
    }

    private List<SessionMessageRow> retainNewestWithinBudget(List<SessionMessageRow> newestFirst) {
        List<SessionMessageRow> result = new ArrayList<>();
        int used = 0;
        for (SessionMessageRow row : newestFirst) {
            int length = summarize(row == null ? null : row.getContent()).length();
            if (!result.isEmpty() && used + length > maxHistoryChars) break;
            result.add(row);
            used += length;
        }
        return result;
    }

    /**
     * 最新消息是尚未标注意图的 user message 时，视为当前正在处理的输入而不是历史上下文。
     * 用户消息的 intent 在写入时为 null；正常完成一轮后最新消息会变成 assistant，因此不会误删历史轮次。
     */
    private boolean isCurrentPendingUserMessage(SessionMessageRow row) {
        return row != null
                && "user".equalsIgnoreCase(row.getRole())
                && (row.getIntent() == null || row.getIntent().isBlank());
    }

    /** 将数据库消息行映射为 IntentAgent 使用的短期上下文摘要。 */
    private ConversationTurn toConversationTurn(SessionMessageRow row) {
        return new ConversationTurn(
                row.getRole(),
                parseIntent(row.getIntent()),
                summarize(row.getContent()),
                row.getCreatedAt() == null
                        ? System.currentTimeMillis()
                        : row.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        );
    }

    /** 解析消息 intent，脏数据返回 null，避免影响主链路。 */
    private Intent parseIntent(String intent) {
        try {
            return intent == null || intent.isBlank() ? null : Intent.valueOf(intent);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 将长文本压缩为最多 120 字符的摘要，减少 IntentAgent 输入 token。 */
    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r", "").replace("\n", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }
}

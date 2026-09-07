package com.city.service.session;

import com.city.enums.Intent;
import com.city.mapper.SessionMapper;
import com.city.model.ConversationTurn;
import com.city.model.SessionMessageRow;
import com.city.util.JsonService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceHistoryTest {

    @Test
    void recentHistoryShouldExcludeCurrentPendingUserMessage() {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionService service = new SessionService(mapper, mock(JsonService.class), 10);

        SessionMessageRow current = message(4L, "user", "西安", null, "trace_current", 4);
        SessionMessageRow previousAssistant = message(
                3L, "assistant", "你想看哪个城市的活动？", Intent.ACTIVITY_PLAN.name(), "trace_prev", 3);
        SessionMessageRow previousUser = message(
                2L, "user", "帮我安排周六一天", null, "trace_prev", 2);
        SessionMessageRow olderAssistant = message(
                1L, "assistant", "可以，先告诉我城市。", Intent.ACTIVITY_PLAN.name(), "trace_old", 1);

        // Mapper 按 created_at DESC 返回；服务为排除当前消息会多取 1 条。
        when(mapper.listRecentMessages("sess_test", 1L, 4))
                .thenReturn(List.of(current, previousAssistant, previousUser, olderAssistant));

        List<ConversationTurn> history = service.recentConversationTurns("sess_test", 1L, 3);

        assertEquals(3, history.size());
        assertEquals("可以，先告诉我城市。", history.get(0).summary());
        assertEquals("帮我安排周六一天", history.get(1).summary());
        assertEquals("你想看哪个城市的活动？", history.get(2).summary());
        assertFalse(history.stream().anyMatch(turn -> "西安".equals(turn.summary())));
        verify(mapper).listRecentMessages("sess_test", 1L, 4);
    }

    @Test
    void recentHistoryShouldKeepLatestAssistantWhenNoCurrentUserMessageExists() {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionService service = new SessionService(mapper, mock(JsonService.class), 10);

        SessionMessageRow latestAssistant = message(
                3L, "assistant", "推荐结果", Intent.MEAL_RECOMMENDATION.name(), "trace_3", 3);
        SessionMessageRow previousUser = message(2L, "user", "西安看展", null, "trace_2", 2);
        SessionMessageRow olderAssistant = message(
                1L, "assistant", "上一轮回复", Intent.MEAL_RECOMMENDATION.name(), "trace_1", 1);

        when(mapper.listRecentMessages("sess_test", 1L, 4))
                .thenReturn(List.of(latestAssistant, previousUser, olderAssistant));

        List<ConversationTurn> history = service.recentConversationTurns("sess_test", 1L, 3);

        assertEquals(3, history.size());
        assertEquals("上一轮回复", history.get(0).summary());
        assertEquals("西安看展", history.get(1).summary());
        assertEquals("推荐结果", history.get(2).summary());
    }

    private SessionMessageRow message(Long id,
                                      String role,
                                      String content,
                                      String intent,
                                      String traceId,
                                      int second) {
        SessionMessageRow row = new SessionMessageRow();
        row.setId(id);
        row.setSessionId("sess_test");
        row.setRole(role);
        row.setContent(content);
        row.setIntent(intent);
        row.setAgentTraceId(traceId);
        row.setCreatedAt(LocalDateTime.of(2026, 9, 7, 16, 0, second));
        return row;
    }
}

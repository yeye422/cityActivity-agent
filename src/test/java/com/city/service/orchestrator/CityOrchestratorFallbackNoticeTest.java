package com.city.service.orchestrator;

import com.city.enums.SourceMode;
import com.city.model.ResponseResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityOrchestratorFallbackNoticeTest {

    @Test
    void fallbackNoticeShowsEffectiveRetrievalConditionsAndInvitesCorrection() {
        SlotBundle slots = new SlotBundle(
                List.of("西安"),
                List.of("曲江"),
                List.of("放松"),
                List.of(),
                List.of(),
                List.of("展览"),
                List.of("安静"),
                List.of(),
                List.of("室内")
        );
        SlotBundle excluded = new SlotBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("电影"), List.of(), List.of(), List.of()
        );
        TimeConstraint time = new TimeConstraint(
                "周六下午",
                LocalDate.of(2026, 9, 12),
                LocalDate.of(2026, 9, 12),
                LocalTime.of(14, 0),
                LocalTime.of(18, 0),
                LocalDateTime.of(2026, 9, 7, 20, 0)
        );

        SessionState state = SessionState.fresh("session-1", 1L, SourceMode.PUBLIC)
                .withSlots(slots)
                .withExcludedSlots(excluded)
                .withUnconstrainedSlots(Set.of("budget"))
                .withTimeConstraint(time);

        String notice = CityOrchestratorService.fallbackParsingNotice(state);

        assertTrue(notice.contains("这轮我采用了保守解析"));
        assertTrue(notice.contains("数据源=公共活动库"));
        assertTrue(notice.contains("城市=西安"));
        assertTrue(notice.contains("区域=曲江"));
        assertTrue(notice.contains("活动类型=展览"));
        assertTrue(notice.contains("排除活动类型=电影"));
        assertTrue(notice.contains("预算=不限"));
        assertTrue(notice.contains("日期=2026-09-12"));
        assertTrue(notice.contains("时段=14:00-18:00"));
        assertTrue(notice.contains("可以继续补充或修改"));
    }

    @Test
    void prependFallbackNoticeKeepsResponsePayload() {
        SessionState state = SessionState.fresh("session-1", 1L, SourceMode.PUBLIC)
                .withSlots(new SlotBundle(
                        List.of("西安"), List.of(), List.of(), List.of(), List.of(),
                        List.of("展览"), List.of(), List.of(), List.of()
                ));
        ResponseResult original = new ResponseResult("推荐正文", List.of(), "WAIT_USER");

        ResponseResult actual = CityOrchestratorService.prependFallbackParsingNotice(original, state);

        assertTrue(actual.speechText().startsWith("这轮我采用了保守解析"));
        assertTrue(actual.speechText().endsWith("推荐正文"));
        assertEquals(original.displayBlocks(), actual.displayBlocks());
        assertEquals(original.nextAction(), actual.nextAction());
    }
}

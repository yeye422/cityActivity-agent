package com.city.service.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class TimeExpressionParserTest {
    private final TimeExpressionParser parser = new TimeExpressionParser();

    @Test
    void parsesRelativeTomorrowDeterministically() {
        var result = parser.parse("明天晚上");
        assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1), result.dateStart());
        assertEquals(18, result.startTime().getHour());
    }

    @Test
    void recognizesClearTimeRequest() {
        assertTrue(parser.clearRequested("不限时间"));
        assertFalse(parser.clearRequested("明天下午"));
    }

    @Test
    void parsesTimeOnlyConstraint() {
        var result = parser.parse("晚上找个活动");
        assertNull(result.dateStart());
        assertEquals(18, result.startTime().getHour());
        assertEquals(23, result.endTime().getHour());
        assertTrue(result.hasTime());
        assertTrue(result.hasConstraint());
    }
}

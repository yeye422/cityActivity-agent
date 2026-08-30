package com.city.service.intent;

import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentAgentServicePromptTest {

    @Test
    void promptShouldEnforceCurrentTurnPatchSemantics() {
        IntentAgentService service = new IntentAgentService(null, null, null, null, "qwen-turbo");
        SlotBundle knownSlots = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        TimeConstraint knownTime = new TimeConstraint(
                "下午到晚上",
                null,
                null,
                LocalTime.of(14, 0),
                LocalTime.of(23, 0),
                LocalDateTime.of(2026, 8, 30, 13, 0));

        String prompt = ReflectionTestUtils.invokeMethod(
                service,
                "buildUserPrompt",
                1L,
                "sess_test",
                "不限",
                knownSlots,
                knownTime,
                List.of(),
                Map.of("city", List.of("西安"), "budget", List.of("100元内", "200元内"))
        );

        assertNotNull(prompt);
        assertTrue(prompt.contains("不是当前完整会话状态快照"));
        assertTrue(prompt.contains("历史普通条件和历史时间条件只用于理解指代"));
        assertTrue(prompt.contains("历史已生效值不要抄入本轮 slots"));
        assertTrue(prompt.contains("CLEAR 表示用户明确取消该字段限制，values 必须为 []"));
        assertTrue(prompt.contains("当前用户只说“不限”，则 city 不要复制到 slots"));
        assertTrue(prompt.contains("temporal 必须 KEEP/KEEP"));
    }
}

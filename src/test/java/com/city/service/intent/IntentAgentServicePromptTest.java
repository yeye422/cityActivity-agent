package com.city.service.intent;

import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentAgentServicePromptTest {

    @Test
    void promptShouldEnforceCurrentTurnPatchSemantics() {
        IntentAgentService service = new IntentAgentService(null, null, null, null, "qwen-turbo");
        SlotBundle knownSlots = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
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
        assertTrue(prompt.contains("intent 只能是 ACTIVITY_RECOMMENDATION、ACTIVITY_ADJUST、ACTIVITY_PLAN、OTHER"));
        assertTrue(prompt.contains("信息不足不是独立 intent"));
        assertTrue(prompt.contains("安全风险不是独立 intent"));
        assertTrue(prompt.contains("operations 是九维普通属性唯一的状态变更协议"));
        assertTrue(prompt.contains("历史已生效值不要重复写入 operations"));
        assertTrue(prompt.contains("普通正向新增使用 ADD"));
        assertTrue(prompt.contains("CLEAR 的 values 必须为 []"));
        assertTrue(prompt.contains("纯“换一批”必须是 ACTIVITY_ADJUST + operations=[] + temporal KEEP/KEEP"));
        assertFalse(prompt.contains("CLARIFY_NEEDED"));
        assertFalse(prompt.contains("HEALTH_RISK"));
        assertFalse(prompt.contains("顶层只能包含 intent、slots"));
    }
}

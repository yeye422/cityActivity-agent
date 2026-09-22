package com.city.service.memory;

import com.city.exception.CityException;
import com.city.model.PreferenceFactRequest;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 长期记忆写入策略。
 *
 * <p>显式用户写入统一归一化为 EXPLICIT；未来 Agent/Intent 层只能提交 AGENT_CONFIRMED，
 * 且只允许相对稳定的体验偏好槽位。预算、城市、地点等上下文相关条件禁止由 Agent 自动持久化。</p>
 */
@Component
public final class MemoryPolicy {

    public static final String EXPLICIT = "EXPLICIT";
    public static final String AGENT_CONFIRMED = "AGENT_CONFIRMED";

    private static final Set<String> AGENT_WRITABLE_STABLE_SLOTS = Set.of(
            "experienceGoal",
            "companion",
            "activityType",
            "style",
            "duration",
            "feature"
    );

    /** 当前偏好 API 属于用户显式写入，不信任请求体自带 source，统一覆盖为 EXPLICIT。 */
    public PreferenceFactRequest explicit(PreferenceFactRequest request) {
        if (request == null) throw new CityException("偏好内容不能为空");
        return new PreferenceFactRequest(
                request.slotName(),
                request.slotValue(),
                request.polarity(),
                EXPLICIT
        );
    }

    /**
     * 未来 IntentAgent/MemoryProposal 的确认写入入口。
     * 未经明确确认的 AGENT_INFERRED / AUTO 等 source 一律不进入长期记忆。
     */
    public PreferenceFactRequest confirmedAgentWrite(PreferenceFactRequest request) {
        if (request == null) throw new CityException("偏好内容不能为空");
        String slotName = request.slotName() == null ? "" : request.slotName().trim();
        if (!AGENT_WRITABLE_STABLE_SLOTS.contains(slotName)) {
            throw new CityException("该条件不允许由 Agent 写入长期记忆: " + slotName);
        }
        return new PreferenceFactRequest(
                slotName,
                request.slotValue(),
                request.polarity(),
                AGENT_CONFIRMED
        );
    }

    public boolean isAgentWritableStableSlot(String slotName) {
        return slotName != null && AGENT_WRITABLE_STABLE_SLOTS.contains(slotName.trim());
    }
}

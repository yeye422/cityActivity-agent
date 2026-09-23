package com.city.service.memory;

import com.city.exception.CityException;
import com.city.model.MemoryMutationProposal;
import com.city.model.PreferenceFactRequest;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 长期记忆写入策略。 */
@Component
public final class MemoryPolicy {

    public static final String EXPLICIT = "EXPLICIT";
    public static final String AGENT_CONFIRMED = "AGENT_CONFIRMED";

    private static final Set<String> AGENT_WRITABLE_STABLE_SLOTS = Set.of(
            "experienceGoal",
            "activityType",
            "style",
            "feature"
    );

    public PreferenceFactRequest explicit(PreferenceFactRequest request) {
        if (request == null) throw new CityException("偏好内容不能为空");
        return new PreferenceFactRequest(
                request.slotName(),
                request.slotValue(),
                request.polarity(),
                EXPLICIT
        );
    }

    public PreferenceFactRequest confirmedAgentWrite(MemoryMutationProposal proposal) {
        if (proposal == null) throw new CityException("长期记忆建议不能为空");
        if (!proposal.explicitLongTerm()) {
            throw new CityException("未明确表达长期偏好，不允许自动写入长期记忆");
        }
        String slotName = proposal.slotName();
        if (!AGENT_WRITABLE_STABLE_SLOTS.contains(slotName)) {
            throw new CityException("该条件不允许由 Agent 写入长期记忆: " + slotName);
        }
        if (proposal.slotValue().isBlank()) {
            throw new CityException("长期记忆值不能为空");
        }
        return new PreferenceFactRequest(
                slotName,
                proposal.slotValue(),
                proposal.polarity(),
                AGENT_CONFIRMED
        );
    }

    public boolean isAgentWritableStableSlot(String slotName) {
        return slotName != null && AGENT_WRITABLE_STABLE_SLOTS.contains(slotName.trim());
    }
}

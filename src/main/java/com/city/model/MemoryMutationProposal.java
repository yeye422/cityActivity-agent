package com.city.model;

import com.city.enums.PreferencePolarity;

/**
 * IntentAgent 提交的长期记忆写入建议。
 *
 * <p>Proposal 只是候选；最终是否允许持久化由 MemoryPolicy 决定。</p>
 */
public record MemoryMutationProposal(
        String slotName,
        String slotValue,
        PreferencePolarity polarity,
        boolean explicitLongTerm,
        String raw
) {
    public MemoryMutationProposal {
        slotName = slotName == null ? "" : slotName.trim();
        slotValue = slotValue == null ? "" : slotValue.trim();
        polarity = polarity == null ? PreferencePolarity.PREFER : polarity;
        raw = raw == null ? "" : raw.trim();
    }
}

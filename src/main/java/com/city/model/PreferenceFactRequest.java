package com.city.model;

import com.city.enums.PreferencePolarity;

/** 显式新增或恢复一条长期偏好。 */
public record PreferenceFactRequest(
        String slotName,
        String slotValue,
        PreferencePolarity polarity,
        String source
) {
}

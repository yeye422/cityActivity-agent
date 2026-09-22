package com.city.model;

import com.city.enums.PreferencePolarity;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户跨会话复用的结构化偏好事实。 */
@Data
public class PreferenceFact {
    private Long id;
    private Long userId;
    private String slotName;
    private String slotValue;
    private PreferencePolarity polarity;
    private String source;
    private Integer version;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

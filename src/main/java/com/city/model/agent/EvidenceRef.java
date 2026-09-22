package com.city.model.agent;

import java.time.Instant;

/** 指向权威活动、场次、天气或规划证据的不可变引用。 */
public record EvidenceRef(
        EvidenceType type,
        String id,
        String fingerprint,
        Instant createdAt
) {
    public EvidenceRef {
        if (type == null) throw new IllegalArgumentException("证据类型不能为空");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("证据ID不能为空");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}

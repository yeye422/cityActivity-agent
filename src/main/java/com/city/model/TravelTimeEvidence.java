package com.city.model;

/**
 * 两个真实场地之间的旅行时间证据。
 * Solver 只消费外部已验证结果，不自行根据地址或距离猜测耗时。
 */
public record TravelTimeEvidence(
        Long fromVenueId,
        Long toVenueId,
        int durationMinutes,
        String source
) {
    public TravelTimeEvidence {
        if (fromVenueId == null || toVenueId == null) {
            throw new IllegalArgumentException("旅行时间证据必须包含起终点 venueId");
        }
        if (durationMinutes < 0) {
            throw new IllegalArgumentException("旅行时间不能为负数");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("旅行时间证据必须声明来源");
        }
    }
}

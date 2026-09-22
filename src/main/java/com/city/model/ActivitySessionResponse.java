package com.city.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 活动在某个场地的一次具体可参加场次。 */
public record ActivitySessionResponse(
        Long sessionId,
        Long activityId,
        Long venueId,
        String venueName,
        String venueType,
        String city,
        String district,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDateTime startAt,
        LocalDateTime endAt,
        BigDecimal price,
        Integer remainingSeats,
        String status,
        String registrationUrl,
        String priceNote
) {
    /** 兼容现有测试/调用方；旧构造器没有场地坐标时按未知处理。 */
    public ActivitySessionResponse(
            Long sessionId,
            Long activityId,
            Long venueId,
            String venueName,
            String venueType,
            String city,
            String district,
            String address,
            LocalDateTime startAt,
            LocalDateTime endAt,
            BigDecimal price,
            Integer remainingSeats,
            String status,
            String registrationUrl,
            String priceNote
    ) {
        this(sessionId, activityId, venueId, venueName, venueType, city, district, address,
                null, null, startAt, endAt, price, remainingSeats, status, registrationUrl, priceNote);
    }

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}

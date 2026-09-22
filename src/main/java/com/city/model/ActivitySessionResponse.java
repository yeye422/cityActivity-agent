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
        LocalDateTime startAt,
        LocalDateTime endAt,
        BigDecimal price,
        Integer remainingSeats,
        String status,
        String registrationUrl,
        String priceNote
) {
}

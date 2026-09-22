package com.city.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** MyBatis 联表查询行，仅供场次列表接口使用。 */
@Data
public class ActivitySessionRow {
    private Long sessionId;
    private Long activityId;
    private Long venueId;
    private String venueName;
    private String venueType;
    private String city;
    private String district;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private BigDecimal price;
    private Integer remainingSeats;
    private String status;
    private String registrationUrl;
    private String priceNote;

    public ActivitySessionResponse toResponse() {
        return new ActivitySessionResponse(sessionId, activityId, venueId, venueName, venueType, city, district,
                address, latitude, longitude, startAt, endAt, price, remainingSeats, status, registrationUrl, priceNote);
    }
}

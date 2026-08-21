package com.city.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ActivityItemRow {
    private Long id;
    private String sourceType;
    private Long ownerUserId;
    private String name;
    private String city;
    private String location;
    private String activityTime;
    private String mood;
    private String scene;
    private String budget;
    private String activityType;
    private String style;
    private String duration;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

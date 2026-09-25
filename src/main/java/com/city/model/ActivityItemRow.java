package com.city.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
public class ActivityItemRow {
    private Long id;
    private String sourceType;
    private Long ownerUserId;
    private String name;
    private String description;
    private String city;
    private String location;
    private String experienceGoal;
    private String companion;
    private String budget;
    private String activityType;
    private String style;
    private String duration;
    private String feature;
    private Integer durationMinutes;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalTime validStartTime;
    private LocalTime validEndTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

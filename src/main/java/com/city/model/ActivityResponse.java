package com.city.model;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalTime;

import com.city.enums.SourceMode;
import com.fasterxml.jackson.annotation.JsonAutoDetect;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class ActivityResponse {
    private Long id;
    private SourceMode sourceType;
    private String name;
    private List<String> city;
    private List<String> location;
    private List<String> mood;
    private List<String> scene;
    private List<String> budget;
    private List<String> activityType;
    private List<String> style;
    private List<String> duration;
    private Integer durationMinutes;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalTime validStartTime;
    private LocalTime validEndTime;
    private double matchScore;

    public static ActivityResponse from(ActivityItem item) {
        SlotBundle slots = item.slots();
        return new ActivityResponse(
                item.id(),
                item.sourceType(),
                item.name(),
                slots.city(),
                slots.location(),
                slots.mood(),
                slots.scene(),
                slots.budget(),
                slots.activityType(),
                slots.style(),
                slots.duration(),
                item.durationMinutes(),
                item.validFrom(),
                item.validTo(),
                item.validStartTime(),
                item.validEndTime(),
                item.matchScore()
        );
    }
}

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
    private String description;
    private List<String> city;
    private List<String> location;
    private List<String> experienceGoal;
    private List<String> companion;
    private List<String> budget;
    private List<String> activityType;
    private List<String> style;
    private List<String> duration;
    private List<String> feature;
    private Integer durationMinutes;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalTime validStartTime;
    private LocalTime validEndTime;
    private double matchScore;

    /** 兼容引入 description 前的构造方式。 */
    public ActivityResponse(Long id,
                            SourceMode sourceType,
                            String name,
                            List<String> city,
                            List<String> location,
                            List<String> experienceGoal,
                            List<String> companion,
                            List<String> budget,
                            List<String> activityType,
                            List<String> style,
                            List<String> duration,
                            List<String> feature,
                            Integer durationMinutes,
                            LocalDate validFrom,
                            LocalDate validTo,
                            LocalTime validStartTime,
                            LocalTime validEndTime,
                            double matchScore) {
        this(id, sourceType, name, null, city, location, experienceGoal, companion, budget,
                activityType, style, duration, feature, durationMinutes,
                validFrom, validTo, validStartTime, validEndTime, matchScore);
    }

    public static ActivityResponse from(ActivityItem item) {
        SlotBundle slots = item.slots();
        return new ActivityResponse(
                item.id(),
                item.sourceType(),
                item.name(),
                item.description(),
                slots.city(),
                slots.location(),
                slots.experienceGoal(),
                slots.companion(),
                slots.budget(),
                slots.activityType(),
                slots.style(),
                slots.duration(),
                slots.feature(),
                item.durationMinutes(),
                item.validFrom(),
                item.validTo(),
                item.validStartTime(),
                item.validEndTime(),
                item.matchScore()
        );
    }
}

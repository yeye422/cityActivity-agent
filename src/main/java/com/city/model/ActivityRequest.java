package com.city.model;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalTime;

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
public class ActivityRequest {
    private String name;
    private List<String> city;
    private List<String> location;
    private List<String> experienceGoal;
    private List<String> companion;
    private List<String> budget;
    private List<String> activityType;
    private List<String> style;
    private List<String> duration;
    private List<String> feature;
    /** 明确活动预计耗时（分钟），用于多时段规划；可为空。 */
    private Integer durationMinutes;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalTime validStartTime;
    private LocalTime validEndTime;

    public SlotBundle toSlots() {
        return new SlotBundle(city, location, experienceGoal, companion, budget, activityType, style, duration, feature);
    }
}

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
    private List<String> mood;
    private List<String> scene;
    private List<String> budget;
    private List<String> activityType;
    private List<String> style;
    private List<String> duration;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalTime validStartTime;
    private LocalTime validEndTime;

    public SlotBundle toSlots() {
        return new SlotBundle(city, location, mood, scene, budget, activityType, style, duration);
    }
}

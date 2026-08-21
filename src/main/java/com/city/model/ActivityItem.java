package com.city.model;

import com.city.enums.SourceMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true)
@AllArgsConstructor
public class ActivityItem {
    private Long id;
    private SourceMode sourceType;
    private Long ownerUserId;
    private String name;
    private SlotBundle slots;
    private double matchScore;

    public double matchScore() {
        return matchScore;
    }
}





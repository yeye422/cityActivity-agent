package com.city.model;

import com.city.enums.SourceMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Accessors(fluent = true)
@AllArgsConstructor
public class ActivityItem {
    private Long id;
    private SourceMode sourceType;
    private Long ownerUserId;
    private String name;
    private SlotBundle slots;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalTime validStartTime;
    private LocalTime validEndTime;
    /** 明确活动时长（分钟）；为空时规划层可按标签/活动类型给出软估算。 */
    private Integer durationMinutes;
    private double matchScore;

    /** 保留旧构造调用兼容；旧数据没有明确时长时 durationMinutes=null。 */
    public ActivityItem(Long id,
                        SourceMode sourceType,
                        Long ownerUserId,
                        String name,
                        SlotBundle slots,
                        LocalDate validFrom,
                        LocalDate validTo,
                        LocalTime validStartTime,
                        LocalTime validEndTime,
                        double matchScore) {
        this(id, sourceType, ownerUserId, name, slots, validFrom, validTo,
                validStartTime, validEndTime, null, matchScore);
    }

    public double matchScore() {
        return matchScore;
    }
}

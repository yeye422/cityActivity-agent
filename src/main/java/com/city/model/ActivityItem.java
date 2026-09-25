package com.city.model;

import com.city.enums.SourceMode;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@AllArgsConstructor
public class ActivityItem {
    private Long id;
    private SourceMode sourceType;
    private Long ownerUserId;
    private String name;
    /** 面向用户和语义检索的自然语言活动描述，可为空。 */
    private String description;
    private SlotBundle slots;
    private LocalDate validFrom;
    private LocalDate validTo;
    private LocalTime validStartTime;
    private LocalTime validEndTime;
    /** 明确活动时长（分钟）；为空时规划层可按标签/活动类型给出软估算。 */
    private Integer durationMinutes;
    private double matchScore;

    /** 兼容引入 description 前的构造方式。 */
    public ActivityItem(Long id,
                        SourceMode sourceType,
                        Long ownerUserId,
                        String name,
                        SlotBundle slots,
                        LocalDate validFrom,
                        LocalDate validTo,
                        LocalTime validStartTime,
                        LocalTime validEndTime,
                        Integer durationMinutes,
                        double matchScore) {
        this(id, sourceType, ownerUserId, name, null, slots,
                validFrom, validTo, validStartTime, validEndTime, durationMinutes, matchScore);
    }

    public double matchScore() {
        return matchScore;
    }
}

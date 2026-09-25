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
    /** 自然语言活动描述，用于前端详情、Agent inspect 和语义检索。 */
    private String description;
    private List<String> city;
    private List<String> location;
    private List<String> experienceGoal;
    private List<String> companion;
    private List<String> budget;
    private List<String> activityType;
    private List<String> style;
    /** 纯活动时长筛选标签。 */
    private List<String> duration;
    /** 室内/户外/近地铁/交通方便等客观特征。 */
    private List<String> feature;
    /** 明确活动预计耗时（分钟），用于多时段规划；可为空。 */
    private Integer durationMinutes;
    private LocalDate validFrom;
    private LocalDate validTo;
    /** 活动级可安排/有效时段；不代表实际活动耗时。 */
    private LocalTime validStartTime;
    private LocalTime validEndTime;

    /** 兼容引入 description 前的构造方式。 */
    public ActivityRequest(String name,
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
                           LocalTime validEndTime) {
        this(name, null, city, location, experienceGoal, companion, budget, activityType,
                style, duration, feature, durationMinutes, validFrom, validTo, validStartTime, validEndTime);
    }

    public SlotBundle toSlots() {
        return new SlotBundle(city, location, experienceGoal, companion, budget, activityType, style, duration, feature);
    }
}

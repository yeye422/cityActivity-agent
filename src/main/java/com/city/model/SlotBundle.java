package com.city.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * CityFlow 九维活动槽位。
 *
 * 时间条件不属于活动属性槽位，统一由 TimeConstraint 表达；
 * 精确活动耗时也不放在 SlotBundle 中，而由 ActivityItem.durationMinutes 表达。
 */
@Data
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SlotBundle {
    /** 城市，如：西安、北京、上海、成都。 */
    private List<String> city;

    /** 纯地理区域，如：曲江、小寨、高新、钟楼。交通便利标签不要放这里。 */
    private List<String> location;

    /** 用户希望获得的体验/目的，如：放松、社交、解压、治愈、刺激。 */
    private List<String> experienceGoal;

    /** 同行关系，如：独处、情侣、朋友、亲子。 */
    private List<String> companion;

    /** 预算，如：免费、100元内、200元内、300元内。 */
    private List<String> budget;

    /** 活动类型，如：电影、展览、演出、桌游、运动、探店。 */
    private List<String> activityType;

    /** 活动氛围/风格，如：安静、文艺、热闹、刺激。 */
    private List<String> style;

    /** 纯活动时长筛选标签，如：1小时内、1-2小时、2-4小时、半天、全天。 */
    private List<String> duration;

    /** 活动客观特征/便利性，如：室内、户外、近地铁、少排队、交通方便。 */
    private List<String> feature;

    public SlotBundle(List<String> city,
                      List<String> location,
                      List<String> experienceGoal,
                      List<String> companion,
                      List<String> budget,
                      List<String> activityType,
                      List<String> style,
                      List<String> duration,
                      List<String> feature) {
        this.city = normalize(city);
        this.location = normalize(location);
        this.experienceGoal = normalize(experienceGoal);
        this.companion = normalize(companion);
        this.budget = normalize(budget);
        this.activityType = normalize(activityType);
        this.style = normalize(style);
        this.duration = normalize(duration);
        this.feature = normalize(feature);
    }

    public static SlotBundle empty() {
        return new SlotBundle(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return city.isEmpty()
                && location.isEmpty()
                && experienceGoal.isEmpty()
                && companion.isEmpty()
                && budget.isEmpty()
                && activityType.isEmpty()
                && style.isEmpty()
                && duration.isEmpty()
                && feature.isEmpty();
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}

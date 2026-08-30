package com.city.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 城市活动槽位。
 *
 * 时间条件不属于活动属性槽位，统一由 TimeConstraint 表达。
 */
@Data
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SlotBundle {
    /** 城市，如：西安、北京、上海、成都 */
    private List<String> city;

    /** 活动位置/区域，如：曲江、小寨、高新、钟楼、近地铁 */
    private List<String> location;


    /** 活动状态，如：放松、社交、解压、治愈、刺激 */
    private List<String> mood;

    /** 同行人，如：独处、情侣、朋友、亲子 */
    private List<String> scene;

    /** 预算，如：免费、100元内、200元内、300元内 */
    private List<String> budget;

    /** 活动类型，如：电影、展览、演出、桌游、运动、探店 */
    private List<String> activityType;

    /** 活动风格，如：安静、文艺、热闹、刺激 */
    private List<String> style;

    /** 活动时长，如：室内、近距离、少排队、交通方便 */
    private List<String> duration;

    public SlotBundle(List<String> city, List<String> location, List<String> mood, List<String> scene, List<String> budget, List<String> activityType, List<String> style, List<String> duration) {
        this.city = normalize(city);
        this.location = normalize(location);
        this.mood = normalize(mood);
        this.scene = normalize(scene);
        this.budget = normalize(budget);
        this.activityType = normalize(activityType);
        this.style = normalize(style);
        this.duration = normalize(duration);
    }

    public static SlotBundle empty() {
        return new SlotBundle(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return city.isEmpty()
                && location.isEmpty()
                && mood.isEmpty()
                && scene.isEmpty()
                && budget.isEmpty()
                && activityType.isEmpty()
                && style.isEmpty()
                && duration.isEmpty();
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

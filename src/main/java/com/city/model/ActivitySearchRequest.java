package com.city.model;

import java.util.List;

import com.city.enums.SourceMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 活动检索请求。
 * 将 sourceMode、userId、slots 和 excludeActivityIds 显式打包，避免个人库和公共库混查。
 */
@Data
@Accessors(fluent = true)
@AllArgsConstructor
public class ActivitySearchRequest {
    /** 本轮明确选择的数据源模式，不能为空。 */
    private SourceMode sourceMode;
    /** 当前用户 ID，PERSONAL 模式必须使用。 */
    private Long userId;
    /** 用于 MySQL JSON_OVERLAPS 的标准槽位。 */
    private SlotBundle slots;
    /** 需要从结果中排除的上一轮推荐活动。 */
    private List<Long> excludeActivityIds;
    /** 用户指定的绝对日期/时段。 */
    private TimeConstraint timeConstraint;
    /** 用户明确排除的标签。 */
    private SlotBundle excludedSlots;

    public ActivitySearchRequest(SourceMode sourceMode, Long userId, SlotBundle slots, List<Long> excludeActivityIds,
                                 TimeConstraint timeConstraint) {
        this(sourceMode, userId, slots, excludeActivityIds, timeConstraint, SlotBundle.empty());
    }

    public ActivitySearchRequest(SourceMode sourceMode, Long userId, SlotBundle slots, List<Long> excludeActivityIds) {
        this(sourceMode, userId, slots, excludeActivityIds, TimeConstraint.empty(), SlotBundle.empty());
    }
}

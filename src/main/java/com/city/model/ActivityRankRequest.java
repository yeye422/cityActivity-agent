package com.city.model;

import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 活动重排请求。
 * <p>
 * 重排层消费合并后的用户槽位、规范化时间和历史排除列表。
 */
@Data
@Accessors(fluent = true)
public class ActivityRankRequest {

    /** 检索层返回的候选活动。 */
    private List<ActivityItem> candidates;

    /** 本轮合并后的用户槽位。 */
    private SlotBundle slots;

    /** TimeResolutionService 产出的规范化时间约束。 */
    private TimeConstraint timeConstraint;

    /** 需要排除的历史推荐 ID。 */
    private List<Long> excludeActivityIds;

    /** 当前用户；为空时不加载跨会话偏好。 */
    private Long userId;

    /** 原始查询文本；为空时跳过 BM25 文本相关性计算。 */
    private String queryText;

    public ActivityRankRequest(List<ActivityItem> candidates,
                               SlotBundle slots,
                               TimeConstraint timeConstraint,
                               List<Long> excludeActivityIds) {
        this(candidates, slots, timeConstraint, excludeActivityIds, null, null);
    }

    public ActivityRankRequest(List<ActivityItem> candidates,
                               SlotBundle slots,
                               TimeConstraint timeConstraint,
                               List<Long> excludeActivityIds,
                               Long userId,
                               String queryText) {
        this.candidates = candidates;
        this.slots = slots;
        this.timeConstraint = timeConstraint;
        this.excludeActivityIds = excludeActivityIds;
        this.userId = userId;
        this.queryText = queryText;
    }
}

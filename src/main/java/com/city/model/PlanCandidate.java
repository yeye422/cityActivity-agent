package com.city.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对 PlanningAgent 提交的精确 activity/session/time 引用完成 Java 硬约束校验后形成的可执行计划。
 */
public record PlanCandidate(
        List<Item> items,
        BigDecimal totalCost
) {
    public PlanCandidate {
        items = items == null ? List.of() : List.copyOf(items);
        totalCost = totalCost == null ? BigDecimal.ZERO : totalCost;
    }

    public int matchedCount() {
        return items.size();
    }

    public record Item(
            ActivityItem activity,
            ActivitySessionResponse session,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        public Item {
            if (activity == null || activity.id() == null) {
                throw new IllegalArgumentException("PlanCandidate activity 不能为空");
            }
            if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
                throw new IllegalArgumentException("PlanCandidate 时间范围无效");
            }
        }
    }
}

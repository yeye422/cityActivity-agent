package com.city.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 对 PlanningAgent 提交的精确 activity/session 引用完成 Java 硬约束校验后形成的可执行计划。
 * Java 不通过枚举替 Agent 选择活动或场次。
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
            String period,
            ActivityItem activity,
            ActivitySessionResponse session
    ) {
        public Item {
            if (activity == null || activity.id() == null) {
                throw new IllegalArgumentException("PlanCandidate activity 不能为空");
            }
        }
    }
}

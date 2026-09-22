package com.city.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 经过 Java PlanningSolver 校验后的可执行规划候选。
 * Response Agent 只能从此类候选中选择和解释，不能自行创建活动或场次。
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

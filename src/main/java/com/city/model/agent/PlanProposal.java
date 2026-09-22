package com.city.model.agent;

import java.util.List;

/** PlanningAgent 提交给 validate_plan Tool 的最小计划引用，只允许引用 Tool 已暴露的活动和场次 ID。 */
public record PlanProposal(
        List<Item> items
) {
    public PlanProposal {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(
            String period,
            Long activityId,
            Long sessionId
    ) {
        public Item {
            period = period == null ? "" : period.trim();
            if (period.isBlank()) {
                throw new IllegalArgumentException("period 不能为空");
            }
            if (activityId == null) {
                throw new IllegalArgumentException("activityId 不能为空");
            }
        }
    }
}

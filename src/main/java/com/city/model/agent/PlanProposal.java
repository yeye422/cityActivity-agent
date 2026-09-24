package com.city.model.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;

/** PlanningAgent 提交给 Java validate_plan 边界的最小计划引用。 */
@Data
@Accessors(fluent = true)
@NoArgsConstructor
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class PlanProposal {
    private List<Item> items = List.of();

    public PlanProposal(List<Item> items) {
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    @Data
    @Accessors(fluent = true)
    @NoArgsConstructor
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class Item {
        private String period;
        private Long activityId;
        private Long sessionId;

        public Item(String period, Long activityId, Long sessionId) {
            this.period = period == null ? "" : period.trim();
            if (this.period.isBlank()) {
                throw new IllegalArgumentException("period 不能为空");
            }
            if (activityId == null) {
                throw new IllegalArgumentException("activityId 不能为空");
            }
            this.activityId = activityId;
            this.sessionId = sessionId;
        }
    }
}

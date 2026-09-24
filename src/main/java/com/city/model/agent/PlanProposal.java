package com.city.model.agent;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** PlanningAgent 提交给 Java validate_plan 的最小计划引用。 */
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
        private Long activityId;
        private Long sessionId;
        private LocalDateTime plannedStartAt;
        private LocalDateTime plannedEndAt;

        public Item(Long activityId,
                    Long sessionId,
                    LocalDateTime plannedStartAt,
                    LocalDateTime plannedEndAt) {
            if (activityId == null) {
                throw new IllegalArgumentException("activityId 不能为空");
            }
            this.activityId = activityId;
            this.sessionId = sessionId;
            this.plannedStartAt = plannedStartAt;
            this.plannedEndAt = plannedEndAt;
        }

        public Item(Long activityId, Long sessionId) {
            this(activityId, sessionId, null, null);
        }
    }
}

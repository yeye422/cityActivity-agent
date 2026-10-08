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
        /** 无固定 session 时使用 ISO-8601 local date-time。 */
        private String plannedStartAt;
        /** 无固定 session 时使用 ISO-8601 local date-time。 */
        private String plannedEndAt;

        public Item(Long activityId,
                    Long sessionId,
                    String plannedStartAt,
                    String plannedEndAt) {
            if (activityId == null) {
                throw new IllegalArgumentException("activityId 不能为空");
            }
            this.activityId = activityId;
            this.sessionId = sessionId;
            this.plannedStartAt = normalize(plannedStartAt);
            this.plannedEndAt = normalize(plannedEndAt);
        }

        public Item(Long activityId,
                    Long sessionId,
                    LocalDateTime plannedStartAt,
                    LocalDateTime plannedEndAt) {
            this(
                    activityId,
                    sessionId,
                    plannedStartAt == null ? null : plannedStartAt.toString(),
                    plannedEndAt == null ? null : plannedEndAt.toString()
            );
        }

        public Item(Long activityId, Long sessionId) {
            this(activityId, sessionId, (String) null, null);
        }

        private String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}

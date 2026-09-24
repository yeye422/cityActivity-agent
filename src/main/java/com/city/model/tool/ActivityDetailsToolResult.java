package com.city.model.tool;

import com.city.model.ActivityItem;
import com.city.model.SlotBundle;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Agent 按需查看的已暴露活动详情，只包含数据库已有事实。 */
public record ActivityDetailsToolResult(
        List<ActivityDetail> activities
) {
    public ActivityDetailsToolResult {
        activities = activities == null ? List.of() : List.copyOf(activities);
    }

    public static ActivityDetailsToolResult from(List<ActivityItem> items) {
        return new ActivityDetailsToolResult(
                items == null ? List.of() : items.stream()
                        .filter(item -> item != null && item.id() != null)
                        .map(ActivityDetail::from)
                        .toList()
        );
    }

    public record ActivityDetail(
            Long activityId,
            String name,
            SlotBundle slots,
            LocalDate validFrom,
            LocalDate validTo,
            LocalTime validStartTime,
            LocalTime validEndTime,
            Integer durationMinutes,
            double matchScore
    ) {
        private static ActivityDetail from(ActivityItem item) {
            return new ActivityDetail(
                    item.id(),
                    item.name(),
                    item.slots(),
                    item.validFrom(),
                    item.validTo(),
                    item.validStartTime(),
                    item.validEndTime(),
                    item.durationMinutes(),
                    item.matchScore()
            );
        }
    }
}

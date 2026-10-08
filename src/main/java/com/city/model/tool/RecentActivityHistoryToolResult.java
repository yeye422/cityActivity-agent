package com.city.model.tool;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;

import java.util.List;

/** 跨会话近期已推荐活动的只读摘要。 */
public record RecentActivityHistoryToolResult(
        List<HistoryItem> items
) {
    public RecentActivityHistoryToolResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static HistoryItem from(ActivityItem item) {
        return new HistoryItem(
                item.id(),
                item.name(),
                item.sourceType(),
                item.slots()
        );
    }

    public record HistoryItem(
            Long activityId,
            String name,
            SourceMode sourceType,
            SlotBundle slots
    ) {}
}

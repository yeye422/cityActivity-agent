package com.city.model.tool;

import com.city.model.ActivityItem;
import com.city.model.SlotBundle;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Agent 可见的活动候选视图，不暴露 Repository/SQL 等内部实现细节。 */
public record RetrievalToolResult(
        String retrievalIntent,
        List<Candidate> candidates
) {
    public RetrievalToolResult {
        retrievalIntent = retrievalIntent == null ? "" : retrievalIntent.trim();
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static RetrievalToolResult from(String retrievalIntent, List<ActivityItem> items) {
        List<Candidate> candidates = items == null ? List.of() : items.stream()
                .filter(item -> item != null && item.id() != null)
                .map(Candidate::from)
                .toList();
        return new RetrievalToolResult(retrievalIntent, candidates);
    }

    public record Candidate(
            Long activityId,
            String name,
            String description,
            SlotBundle slots,
            LocalDate validFrom,
            LocalDate validTo,
            LocalTime validStartTime,
            LocalTime validEndTime,
            Integer durationMinutes,
            double matchScore
    ) {
        private static Candidate from(ActivityItem item) {
            return new Candidate(
                    item.id(),
                    item.name(),
                    item.description(),
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

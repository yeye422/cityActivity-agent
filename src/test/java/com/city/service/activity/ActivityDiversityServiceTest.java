package com.city.service.activity;

import com.city.enums.SourceMode;
import com.city.model.ActivityDiversityResult;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityDiversityServiceTest {
    private final ActivityDiversityService service = new ActivityDiversityService();

    @Test
    void sameScoreShouldPreferNewActivityTypeForSecondChoice() {
        ActivityItem firstFilm = activity(1L, "电影A", "电影", "小寨", LocalTime.of(15, 0), 1.0);
        ActivityItem secondFilm = activity(2L, "电影B", "电影", "高新", LocalTime.of(19, 0), 1.0);
        ActivityItem show = activity(3L, "演出", "演出", "钟楼", LocalTime.of(19, 30), 1.0);

        ActivityDiversityResult result = service.rerank(List.of(firstFilm, secondFilm, show));

        assertEquals(List.of(1L, 3L, 2L), result.ranked().stream().map(ActivityItem::id).toList());
        assertTrue(result.decisions().get(1).reasons().contains("NEW_ACTIVITY_TYPE"));
    }

    @Test
    void nearTieMayUseDiversityButClearlyLowerScoreMustNotJumpAhead() {
        ActivityItem leader = activity(1L, "电影A", "电影", "小寨", LocalTime.of(15, 0), 1.0);
        ActivityItem closeSameType = activity(2L, "电影B", "电影", "高新", LocalTime.of(19, 0), 0.96);
        ActivityItem farDifferentType = activity(3L, "演出", "演出", "钟楼", LocalTime.of(19, 30), 0.80);

        ActivityDiversityResult result = service.rerank(List.of(leader, closeSameType, farDifferentType));

        assertEquals(List.of(1L, 2L, 3L), result.ranked().stream().map(ActivityItem::id).toList());
    }

    @Test
    void newTimeBucketShouldBreakTieAfterTypeAndLocationAreSame() {
        ActivityItem afternoonA = activity(1L, "下午A", "电影", "小寨", LocalTime.of(15, 0), 1.0);
        ActivityItem afternoonB = activity(2L, "下午B", "电影", "小寨", LocalTime.of(16, 0), 1.0);
        ActivityItem evening = activity(3L, "晚上", "电影", "小寨", LocalTime.of(19, 0), 1.0);

        ActivityDiversityResult result = service.rerank(List.of(afternoonA, afternoonB, evening));

        assertEquals(List.of(1L, 3L, 2L), result.ranked().stream().map(ActivityItem::id).toList());
        assertTrue(result.decisions().get(1).reasons().contains("NEW_TIME_BUCKET"));
    }

    private ActivityItem activity(Long id,
                                  String name,
                                  String activityType,
                                  String location,
                                  LocalTime start,
                                  double score) {
        SlotBundle slots = new SlotBundle(
                List.of("西安"), List.of(location), List.of(), List.of(),
                List.of(), List.of(activityType), List.of(), List.of());
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, name, slots,
                null, null, start, start.plusHours(2), null, score);
    }
}

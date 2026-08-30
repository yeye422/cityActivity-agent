package com.city.service.activity;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivityRankResult;
import com.city.model.ActivityRankScore;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityRankServiceTest {
    private final ActivityRankService service = new ActivityRankService();

    @Test
    void fullSlotMatchShouldRankAbovePartialMatch() {
        SlotBundle query = slots(List.of("西安"), List.of(), List.of("文艺", "安静"), List.of(), List.of());
        ActivityItem full = activity(1L, "完整匹配", query, LocalTime.of(15, 0), LocalTime.of(17, 0));
        ActivityItem partial = activity(2L, "部分匹配",
                slots(List.of("西安"), List.of(), List.of("文艺"), List.of(), List.of()),
                LocalTime.of(15, 0), LocalTime.of(17, 0));

        ActivityRankResult result = service.rank(new ActivityRankRequest(
                List.of(partial, full), query, TimeConstraint.empty(), List.of()));

        assertEquals(List.of(1L, 2L), result.ranked().stream().map(ActivityItem::id).toList());
        assertTrue(result.scores().get(0).slotScore() > result.scores().get(1).slotScore());
        assertNull(result.scores().get(0).timeScore());
    }

    @Test
    void activityFullyInsideUserWindowShouldRankAbovePartialOverlap() {
        SlotBundle query = slots(List.of("西安"), List.of(), List.of(), List.of(), List.of());
        ActivityItem partial = activity(14L, "部分重叠", query,
                LocalTime.of(13, 30), LocalTime.of(17, 30));
        ActivityItem full = activity(15L, "完整覆盖", query,
                LocalTime.of(19, 30), LocalTime.of(21, 30));
        TimeConstraint time = new TimeConstraint(
                "下午到晚上", null, null,
                LocalTime.of(14, 0), LocalTime.of(23, 0), LocalDateTime.now());

        ActivityRankResult result = service.rank(new ActivityRankRequest(
                List.of(partial, full), query, time, List.of()));

        assertEquals(List.of(15L, 14L), result.ranked().stream().map(ActivityItem::id).toList());
        ActivityRankScore fullScore = result.scores().get(0);
        ActivityRankScore partialScore = result.scores().get(1);
        assertEquals(1.0, fullScore.timeScore(), 0.0001);
        assertEquals(0.875, partialScore.timeScore(), 0.0001);
        assertEquals(1.0, fullScore.finalScore(), 0.0001);
        assertEquals(0.975, partialScore.finalScore(), 0.0001);
    }

    @Test
    void badWeatherShouldPreferIndoorFeatureWithoutFilteringOutdoor() {
        SlotBundle query = slots(List.of("西安"), List.of(), List.of(), List.of(), List.of());
        ActivityItem indoor = activity(1L, "室内活动",
                slots(List.of("西安"), List.of(), List.of(), List.of(), List.of("室内")),
                LocalTime.of(15, 0), LocalTime.of(17, 0));
        ActivityItem outdoor = activity(2L, "户外活动",
                slots(List.of("西安"), List.of(), List.of(), List.of(), List.of("户外")),
                LocalTime.of(15, 0), LocalTime.of(17, 0));

        ActivityRankResult result = service.rank(
                new ActivityRankRequest(List.of(outdoor, indoor), query, TimeConstraint.empty(), List.of()),
                WeatherRecommendationContext.indoorPriority("小雨，优先室内"));

        assertEquals(List.of(1L, 2L), result.ranked().stream().map(ActivityItem::id).toList());
        assertEquals(2, result.ranked().size());
        assertEquals(1.0, result.scores().get(0).finalScore(), 0.0001);
        assertEquals(0.82, result.scores().get(1).finalScore(), 0.0001);
        assertEquals(WeatherRecommendationContext.Status.INDOOR_PRIORITY,
                result.scores().get(0).weatherStatus());
    }

    @Test
    void rankShouldPreserveExplicitDurationMinutesForPlanLayer() {
        SlotBundle query = slots(
                List.of("西安"), List.of("展览"), List.of("文艺"), List.of("2-4小时"), List.of("室内"));
        ActivityItem item = new ActivityItem(
                7L, SourceMode.PUBLIC, null, "明确时长活动", query,
                null, null, LocalTime.of(14, 0), LocalTime.of(18, 0), 150, 0.0);

        ActivityRankResult result = service.rank(new ActivityRankRequest(
                List.of(item), query, TimeConstraint.empty(), List.of()));

        assertEquals(150, result.ranked().getFirst().durationMinutes());
        assertEquals(List.of("2-4小时"), result.ranked().getFirst().slots().duration());
        assertEquals(List.of("室内"), result.ranked().getFirst().slots().feature());
    }

    @Test
    void excludeIdsShouldBeRemovedBeforeScoring() {
        SlotBundle query = slots(List.of("西安"), List.of(), List.of(), List.of(), List.of());
        ActivityItem first = activity(1L, "已推荐", query, LocalTime.of(15, 0), LocalTime.of(17, 0));
        ActivityItem second = activity(2L, "新活动", query, LocalTime.of(15, 0), LocalTime.of(17, 0));

        ActivityRankResult result = service.rank(new ActivityRankRequest(
                List.of(first, second), query, TimeConstraint.empty(), List.of(1L)));

        assertEquals(List.of(2L), result.ranked().stream().map(ActivityItem::id).toList());
    }

    @Test
    void cheaperActivityShouldFullySatisfyHigherBudgetUpperBound() {
        SlotBundle query = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(),
                List.of("200元内"), List.of(), List.of(), List.of(), List.of());
        SlotBundle cheapSlots = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(),
                List.of("100元内"), List.of(), List.of(), List.of(), List.of());
        SlotBundle expensiveSlots = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(),
                List.of("300元内"), List.of(), List.of(), List.of(), List.of());

        ActivityItem cheap = activity(1L, "100元活动", cheapSlots, null, null);
        ActivityItem expensive = activity(2L, "300元活动", expensiveSlots, null, null);

        ActivityRankResult result = service.rank(new ActivityRankRequest(
                List.of(expensive, cheap), query, TimeConstraint.empty(), List.of()));

        assertEquals(List.of(1L, 2L), result.ranked().stream().map(ActivityItem::id).toList());
        assertEquals(1.0, result.scores().get(0).slotScore(), 0.0001);
        assertTrue(result.scores().get(0).slotScore() > result.scores().get(1).slotScore());
    }

    private ActivityItem activity(Long id, String name, SlotBundle slots, LocalTime start, LocalTime end) {
        return new ActivityItem(id, SourceMode.PUBLIC, null, name, slots,
                null, null, start, end, 0.0);
    }

    private SlotBundle slots(List<String> city,
                             List<String> activityType,
                             List<String> style,
                             List<String> duration,
                             List<String> feature) {
        return new SlotBundle(
                city, List.of(), List.of(), List.of(),
                List.of(), activityType, style, duration, feature);
    }
}

package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivityRankResult;
import com.city.model.ActivitySearchRequest;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityPlanServiceTest {

    @Test
    void shouldResolveAfternoonToEveningAsTwoPlanPeriods() {
        ActivityPlanService service = new ActivityPlanService(
                mock(ActivitySearchService.class),
                mock(ActivityRankService.class));
        TimeConstraint timeConstraint = new TimeConstraint(
                "下午到晚上",
                null,
                null,
                LocalTime.of(12, 0),
                LocalTime.of(23, 0),
                null
        );

        assertEquals(
                List.of("下午", "晚上"),
                service.resolveActivityTimes(SlotBundle.empty(), timeConstraint)
        );
    }

    @Test
    void shouldPreserveWeekendPrefixWhenTimeRangeCoversMultiplePeriods() {
        ActivityPlanService service = new ActivityPlanService(
                mock(ActivitySearchService.class),
                mock(ActivityRankService.class));
        LocalDate saturday = LocalDate.of(2026, 9, 5);
        TimeConstraint timeConstraint = new TimeConstraint(
                "周六下午到晚上",
                saturday,
                saturday,
                LocalTime.of(12, 0),
                LocalTime.of(23, 0),
                null
        );

        assertEquals(
                List.of("周六下午", "周六晚上"),
                service.resolveActivityTimes(SlotBundle.empty(), timeConstraint)
        );
    }

    @Test
    void planSearchShouldPreserveExcludedSlotsAndReuseWeatherRanking() {
        ActivitySearchService searchService = mock(ActivitySearchService.class);
        ActivityRankService rankService = mock(ActivityRankService.class);
        ActivityPlanService service = new ActivityPlanService(searchService, rankService);

        SlotBundle querySlots = new SlotBundle(
                List.of("上海"), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of("室内"));
        SlotBundle excludedSlots = new SlotBundle(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of("展览"), List.of(), List.of());
        WeatherRecommendationContext weather = WeatherRecommendationContext.indoorPriority("暴雨，优先室内");
        ActivityItem activity = new ActivityItem(
                1L,
                SourceMode.PUBLIC,
                null,
                "室内电影",
                new SlotBundle(
                        List.of("上海"), List.of(), List.of(), List.of(),
                        List.of(), List.of("电影"), List.of(), List.of("室内")),
                null,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(11, 0),
                1.0
        );

        when(searchService.search(any(ActivitySearchRequest.class))).thenReturn(List.of(activity));
        when(rankService.rank(any(ActivityRankRequest.class), same(weather)))
                .thenReturn(new ActivityRankResult(List.of(activity), List.of()));

        List<ActivityPlanService.PlannedActivity> result = service.planActivities(
                SourceMode.PUBLIC,
                1L,
                querySlots,
                excludedSlots,
                List.of("上午"),
                TimeConstraint.empty(),
                weather
        );

        ArgumentCaptor<ActivitySearchRequest> searchCaptor = ArgumentCaptor.forClass(ActivitySearchRequest.class);
        verify(searchService).search(searchCaptor.capture());
        assertEquals(excludedSlots, searchCaptor.getValue().excludedSlots());
        verify(rankService).rank(any(ActivityRankRequest.class), same(weather));
        assertEquals(1L, result.getFirst().activity().id());
    }
}

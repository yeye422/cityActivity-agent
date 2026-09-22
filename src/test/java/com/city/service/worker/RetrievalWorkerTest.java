package com.city.service.worker;

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

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalWorkerTest {

    @Test
    void shouldRetrieveRankAndLimitPlanningCandidatesInsideWorkerBoundary() {
        ActivitySearchService searchService = mock(ActivitySearchService.class);
        ActivityRankService rankService = mock(ActivityRankService.class);
        RetrievalWorker worker = new RetrievalWorker(searchService, rankService);

        SlotBundle slots = new SlotBundle(
                List.of("西安"), List.of("高新"), List.of("放松"), List.of("朋友"),
                List.of("200元内"), List.of("展览"), List.of("安静"), List.of("1-2小时"), List.of("室内"));
        TimeConstraint time = new TimeConstraint(
                "周六下午", null, null, LocalTime.of(14, 0), LocalTime.of(18, 0), null);
        ActivitySearchRequest request = new ActivitySearchRequest(
                SourceMode.PUBLIC, 1L, slots, List.of(), time, SlotBundle.empty());
        WeatherRecommendationContext weather = WeatherRecommendationContext.indoorPriority("下雨，优先室内");

        ActivityItem first = new ActivityItem(
                1L, SourceMode.PUBLIC, null, "候选1", slots,
                null, null, LocalTime.of(14, 0), LocalTime.of(16, 0), 120, 0.9);
        ActivityItem second = new ActivityItem(
                2L, SourceMode.PUBLIC, null, "候选2", slots,
                null, null, LocalTime.of(16, 0), LocalTime.of(18, 0), 120, 0.8);

        when(searchService.search(same(request))).thenReturn(List.of(first, second));
        when(rankService.rank(any(ActivityRankRequest.class), same(weather)))
                .thenReturn(new ActivityRankResult(List.of(first, second), List.of()));

        List<ActivityItem> result = worker.retrieveRanked(request, weather, 1);

        assertEquals(List.of(1L), result.stream().map(ActivityItem::id).toList());
        verify(searchService).search(same(request));
        verify(rankService).rank(any(ActivityRankRequest.class), same(weather));
    }
}

package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivityRankResult;
import com.city.model.ActivitySearchRequest;
import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import com.city.service.activity.ActivitySessionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityPlanServiceTest {

    @Test
    void shouldResolveAfternoonToEveningAsFinePlanWindows() {
        ActivityPlanService service = new ActivityPlanService(
                mock(ActivitySearchService.class),
                mock(ActivityRankService.class),
                mock(ActivitySessionService.class));
        TimeConstraint timeConstraint = new TimeConstraint(
                "下午到晚上",
                null,
                null,
                LocalTime.of(12, 0),
                LocalTime.of(23, 0),
                null
        );

        assertEquals(
                List.of(
                        "周六 12:00-14:00",
                        "周六 14:00-16:00",
                        "周六 16:00-18:00",
                        "周六 18:00-20:00",
                        "周六 20:00-23:00"),
                service.resolveActivityTimes(SlotBundle.empty(), timeConstraint)
        );
    }

    @Test
    void shouldPreserveWeekendPrefixForFineWindows() {
        ActivityPlanService service = new ActivityPlanService(
                mock(ActivitySearchService.class),
                mock(ActivityRankService.class),
                mock(ActivitySessionService.class));
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
                List.of(
                        "周六 12:00-14:00",
                        "周六 14:00-16:00",
                        "周六 16:00-18:00",
                        "周六 18:00-20:00",
                        "周六 20:00-23:00"),
                service.resolveActivityTimes(SlotBundle.empty(), timeConstraint)
        );
    }

    @Test
    void planSearchShouldPreserveNineDimensionsAndReuseWeatherRanking() {
        ActivitySearchService searchService = mock(ActivitySearchService.class);
        ActivityRankService rankService = mock(ActivityRankService.class);
        ActivityPlanService service = new ActivityPlanService(searchService, rankService, mock(ActivitySessionService.class));

        SlotBundle querySlots = new SlotBundle(
                List.of("上海"), List.of("徐汇"), List.of("放松"), List.of("独处"),
                List.of(), List.of("电影"), List.of("安静"), List.of("1-2小时"), List.of("室内"));
        SlotBundle excludedSlots = new SlotBundle(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of("展览"), List.of(), List.of(), List.of("户外"));
        WeatherRecommendationContext weather = WeatherRecommendationContext.indoorPriority("暴雨，优先室内");
        ActivityItem activity = new ActivityItem(
                1L,
                SourceMode.PUBLIC,
                null,
                "室内电影",
                querySlots,
                null,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(11, 0),
                120,
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
                List.of("周六 08:00-10:00"),
                TimeConstraint.empty(),
                weather
        );

        ArgumentCaptor<ActivitySearchRequest> searchCaptor = ArgumentCaptor.forClass(ActivitySearchRequest.class);
        verify(searchService).search(searchCaptor.capture());
        assertEquals(excludedSlots, searchCaptor.getValue().excludedSlots());
        assertEquals(List.of("室内"), searchCaptor.getValue().slots().feature());
        verify(rankService).rank(any(ActivityRankRequest.class), same(weather));
        assertEquals(1L, result.getFirst().activity().id());
    }

    @Test
    void exactDateShouldAttachAvailableSessionsToPlanCandidate() {
        ActivitySearchService searchService = mock(ActivitySearchService.class);
        ActivityRankService rankService = mock(ActivityRankService.class);
        ActivitySessionService sessionService = mock(ActivitySessionService.class);
        ActivityPlanService service = new ActivityPlanService(searchService, rankService, sessionService);

        LocalDate saturday = LocalDate.of(2026, 9, 5);
        ActivityItem activity = new ActivityItem(
                21L,
                SourceMode.PUBLIC,
                null,
                "具体场次演出",
                new SlotBundle(
                        List.of("西安"), List.of("高新"), List.of("社交"), List.of("朋友"),
                        List.of("200元内"), List.of("演出"), List.of("热闹"), List.of("1-2小时"), List.of("室内")),
                saturday,
                saturday,
                LocalTime.of(14, 0),
                LocalTime.of(18, 0),
                120,
                0.95
        );
        ActivitySessionResponse session = new ActivitySessionResponse(
                901L, 21L, 301L, "高新剧场", "剧场", "西安", "高新", null,
                LocalDateTime.of(2026, 9, 5, 14, 30),
                LocalDateTime.of(2026, 9, 5, 16, 0),
                null, 8, "OPEN", null, null
        );

        when(searchService.search(any(ActivitySearchRequest.class))).thenReturn(List.of(activity));
        when(rankService.rank(any(ActivityRankRequest.class), any(WeatherRecommendationContext.class)))
                .thenReturn(new ActivityRankResult(List.of(activity), List.of()));
        when(sessionService.findAvailable(21L, saturday)).thenReturn(List.of(session));

        TimeConstraint time = new TimeConstraint(
                "周六下午", saturday, saturday, LocalTime.of(14, 0), LocalTime.of(16, 0), null);
        List<ActivityPlanService.PlannedActivity> result = service.planActivities(
                SourceMode.PUBLIC, 1L, activity.slots(), SlotBundle.empty(),
                List.of("周六 14:00-16:00"), time, WeatherRecommendationContext.inactive());

        assertEquals(1, result.size());
        assertTrue(result.getFirst().sessionsByActivityId().containsKey(21L));
        assertEquals(901L, result.getFirst().sessionsByActivityId().get(21L).getFirst().sessionId());
        assertEquals(901L, result.getFirst().selectedSession().sessionId());
    }
}

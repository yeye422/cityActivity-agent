package com.city.service.activity;

import com.city.enums.SourceMode;
import com.city.model.ActivityRankResult;
import com.city.model.ActivitySearchRequest;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelaxationSearchServiceTest {

    @Test
    void relaxedSearchShouldPreserveExplicitExcludedSlotsAndFeature() {
        ActivitySearchService searchService = mock(ActivitySearchService.class);
        ActivityRankService rankService = mock(ActivityRankService.class);
        when(searchService.search(any(ActivitySearchRequest.class))).thenReturn(List.of());
        when(rankService.rank(any())).thenReturn(new ActivityRankResult(List.of(), List.of()));

        RelaxationSearchService service = new RelaxationSearchService(searchService, rankService);
        SlotBundle original = new SlotBundle(
                List.of("西安"), List.of(), List.of("放松"), List.of(),
                List.of(), List.of("电影"), List.of("安静"), List.of("1-2小时"), List.of("室内"));
        SlotBundle excluded = new SlotBundle(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of("展览"), List.of(), List.of(), List.of("户外"));

        service.find(
                SourceMode.PUBLIC,
                1L,
                original,
                excluded,
                List.of(),
                TimeConstraint.empty(),
                1
        );

        ArgumentCaptor<ActivitySearchRequest> captor = ArgumentCaptor.forClass(ActivitySearchRequest.class);
        verify(searchService).search(captor.capture());
        assertEquals(excluded, captor.getValue().excludedSlots());
        assertEquals(List.of(), captor.getValue().slots().experienceGoal());
        assertEquals(List.of(), captor.getValue().slots().style());
        assertEquals(List.of("电影"), captor.getValue().slots().activityType());
        assertEquals(List.of("1-2小时"), captor.getValue().slots().duration());
        assertEquals(List.of("室内"), captor.getValue().slots().feature());
    }
}

package com.city.service.retrieval;

import com.city.enums.SourceMode;
import com.city.model.ActivityDiversityResult;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivityRankResult;
import com.city.model.ActivitySearchRequest;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.retrieval.RetrievalRequest;
import com.city.model.retrieval.RetrievalResult;
import com.city.service.activity.ActivityDiversityService;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalPipelineTest {

    @Mock
    private ActivitySearchService activitySearchService;
    @Mock
    private ActivityRankService activityRankService;
    @Mock
    private ActivityDiversityService activityDiversityService;

    @Test
    void shouldReuseExistingSearchRankAndDiversityStagesBehindOnePipeline() {
        RetrievalPipeline pipeline = new RetrievalPipeline(
                activitySearchService,
                activityRankService,
                activityDiversityService
        );

        ActivitySearchRequest searchRequest = new ActivitySearchRequest(
                SourceMode.PUBLIC,
                7L,
                SlotBundle.empty(),
                List.of(99L),
                TimeConstraint.empty(),
                SlotBundle.empty()
        );
        ActivityItem first = mock(ActivityItem.class);
        ActivityItem second = mock(ActivityItem.class);
        List<ActivityItem> raw = List.of(first, second);
        List<ActivityItem> relevanceRanked = List.of(second, first);
        List<ActivityItem> diversified = List.of(first, second);

        when(activitySearchService.search(searchRequest)).thenReturn(raw);
        when(activityRankService.rank(any(ActivityRankRequest.class), any(WeatherRecommendationContext.class)))
                .thenReturn(new ActivityRankResult(relevanceRanked, List.of()));
        when(activityDiversityService.rerank(relevanceRanked))
                .thenReturn(new ActivityDiversityResult(diversified, List.of()));

        RetrievalResult result = pipeline.retrieve(new RetrievalRequest(
                searchRequest,
                "互动 新鲜 约会",
                WeatherRecommendationContext.inactive(),
                1
        ));

        assertEquals(raw, result.rawCandidates());
        assertEquals(relevanceRanked, result.rankedCandidates());
        assertEquals(List.of(first), result.finalCandidates());

        ArgumentCaptor<ActivityRankRequest> captor = ArgumentCaptor.forClass(ActivityRankRequest.class);
        verify(activityRankService).rank(captor.capture(), any(WeatherRecommendationContext.class));
        ActivityRankRequest forwarded = captor.getValue();
        assertEquals(7L, forwarded.userId());
        assertEquals("互动 新鲜 约会", forwarded.queryText());
        assertEquals(List.of(99L), forwarded.excludeActivityIds());
        assertEquals(raw, forwarded.candidates());
    }
}

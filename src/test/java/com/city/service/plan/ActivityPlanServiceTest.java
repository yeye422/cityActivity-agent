package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.context.PlanningHorizon;
import com.city.model.retrieval.RetrievalRequest;
import com.city.model.retrieval.RetrievalResult;
import com.city.service.activity.ActivitySessionService;
import com.city.service.retrieval.RetrievalPipeline;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ActivityPlanServiceTest {

    @Test
    void shouldPreserveExactRequestedRangeInRetrieval() {
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        ActivitySessionService sessionService = mock(ActivitySessionService.class);
        ActivityPlanService service = new ActivityPlanService(pipeline, sessionService);
        when(pipeline.retrieve(any())).thenReturn(emptyResult());

        PlanningHorizon.Range range = new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, 15, 0),
                LocalDateTime.of(2026, 9, 27, 17, 0));

        SlotBundle planningSlots = new SlotBundle(
                List.of("西安"), List.of(), List.of("轻松"), List.of("情侣"),
                List.of(), List.of(), List.of(), List.of("半天"), List.of());
        service.discoverRange(
                SourceMode.PUBLIC, 1L, planningSlots, SlotBundle.empty(),
                range, WeatherRecommendationContext.inactive(), "轻松约会", List.of());

        ArgumentCaptor<RetrievalRequest> captor = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(pipeline).retrieve(captor.capture());
        var time = captor.getValue().searchRequest().timeConstraint();
        assertEquals(LocalDate.of(2026, 9, 27), time.dateStart());
        assertEquals(15, time.startTime().getHour());
        assertEquals(17, time.endTime().getHour());
        assertTrue(captor.getValue().searchRequest().slots().duration().isEmpty());
    }

    @Test
    void shouldExposeOnlySessionsFullyInsideRequestedRange() {
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        ActivitySessionService sessionService = mock(ActivitySessionService.class);
        ActivityPlanService service = new ActivityPlanService(pipeline, sessionService);

        ActivityItem activity = activity(21L, "具体场次演出");
        when(pipeline.retrieve(any())).thenReturn(new RetrievalResult(
                List.of(activity), List.of(activity), List.of(activity), List.of(), List.of()));

        ActivitySessionResponse outside = session(
                900L, 21L,
                LocalDateTime.of(2026, 9, 27, 14, 30),
                LocalDateTime.of(2026, 9, 27, 16, 0));
        ActivitySessionResponse inside = session(
                901L, 21L,
                LocalDateTime.of(2026, 9, 27, 15, 30),
                LocalDateTime.of(2026, 9, 27, 16, 30));
        when(sessionService.findAvailable(21L, LocalDate.of(2026, 9, 27)))
                .thenReturn(List.of(outside, inside));

        PlanningHorizon.Range range = new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, 15, 0),
                LocalDateTime.of(2026, 9, 27, 17, 0));
        ActivityPlanService.CandidateBatch result = service.discoverRange(
                SourceMode.PUBLIC, 1L, activity.slots(), SlotBundle.empty(),
                range, WeatherRecommendationContext.inactive(), "", List.of());

        assertEquals(List.of(901L), result.sessionsByActivityId().get(21L)
                .stream().map(ActivitySessionResponse::sessionId).toList());
    }

    @Test
    void shouldDropSessionBackedActivityWhenNoSessionFitsRequestedRange() {
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        ActivitySessionService sessionService = mock(ActivitySessionService.class);
        ActivityPlanService service = new ActivityPlanService(pipeline, sessionService);

        ActivityItem activity = activity(31L, "晚间演出");
        when(pipeline.retrieve(any())).thenReturn(new RetrievalResult(
                List.of(activity), List.of(activity), List.of(activity), List.of(), List.of()));
        when(sessionService.findAvailable(31L, LocalDate.of(2026, 9, 27)))
                .thenReturn(List.of(session(
                        910L, 31L,
                        LocalDateTime.of(2026, 9, 27, 19, 0),
                        LocalDateTime.of(2026, 9, 27, 21, 0))));

        PlanningHorizon.Range afternoon = new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, 14, 0),
                LocalDateTime.of(2026, 9, 27, 18, 0));
        ActivityPlanService.CandidateBatch result = service.discoverRange(
                SourceMode.PUBLIC, 1L, activity.slots(), SlotBundle.empty(),
                afternoon, WeatherRecommendationContext.inactive(), "", List.of());

        assertTrue(result.candidates().isEmpty());
        assertTrue(result.sessionsByActivityId().isEmpty());
    }

    private RetrievalResult emptyResult() {
        return new RetrievalResult(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, name,
                new SlotBundle(List.of("西安"), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of()),
                null, null, null, null, 90, 0.9);
    }

    private ActivitySessionResponse session(Long sessionId,
                                            Long activityId,
                                            LocalDateTime start,
                                            LocalDateTime end) {
        return new ActivitySessionResponse(
                sessionId, activityId, 301L, "剧场", "INDOOR", "西安", "高新", "地址",
                start, end, null, 8, "OPEN", null, null);
    }
}

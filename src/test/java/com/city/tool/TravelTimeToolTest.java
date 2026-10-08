package com.city.tool;

import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.model.ActivityItem;
import com.city.model.ActivitySessionResponse;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TravelTimeEvidence;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanNotebook;
import com.city.model.context.PlanningHorizon;
import com.city.model.context.PlanningToolContext;
import com.city.model.context.SemanticContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.evidence.PlanningEvidenceRegistry;
import com.city.service.location.AmapTravelTimeService;
import com.city.service.plan.ActivityPlanService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelTimeToolTest {

    @Test
    void shouldResolveOnlyExposedSessionsAndRecordEvidence() {
        AmapTravelTimeService service = mock(AmapTravelTimeService.class);
        PlanningEvidenceRegistry registry = registry();
        PlanningToolContext context = context(registry);
        ActivitySessionResponse from = registry.session(101L, 1001L);
        ActivitySessionResponse to = registry.session(202L, 2002L);
        TravelTimeEvidence expected = new TravelTimeEvidence(11L, 22L, 35, "AMAP_DRIVING");
        when(service.resolve(from, to)).thenReturn(expected);

        TravelTimeEvidence actual = new TravelTimeTool(service, null).getTravelTime(
                101L, 1001L, 202L, 2002L, context);

        assertEquals(expected, actual);
        assertEquals(List.of(expected), registry.travelTimeEvidence());
        assertEquals(1, registry.travelCalls());
        verify(service).resolve(from, to);
    }

    @Test
    void shouldRejectSessionOutsidePlanningEvidence() {
        AmapTravelTimeService service = mock(AmapTravelTimeService.class);
        PlanningToolContext context = context(registry());

        assertThrows(CityException.class, () -> new TravelTimeTool(service, null).getTravelTime(
                101L, 9999L, 202L, 2002L, context));
        verify(service, never()).resolve(any(), any());
    }

    private PlanningEvidenceRegistry registry() {
        ActivityItem first = activity(101L, "陶艺");
        ActivityItem second = activity(202L, "小剧场");
        ActivitySessionResponse firstSession = session(1001L, 101L, 11L, 14, 16);
        ActivitySessionResponse secondSession = session(2002L, 202L, 22L, 17, 19);

        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        registry.record(List.of(new ActivityPlanService.CandidateBatch(
                range(),
                List.of(first, second),
                Map.of(101L, List.of(firstSession), 202L, List.of(secondSession)))));
        return registry;
    }

    private PlanningToolContext context(PlanningEvidenceRegistry registry) {
        SessionState state = SessionState.fresh("session-plan", 9L, SourceMode.PUBLIC);
        VerifiedRequestContext verified = VerifiedRequestContext.from(
                state, "trace-plan", SemanticContext.empty(), WeatherRecommendationContext.inactive());
        PlanningHorizon horizon = new PlanningHorizon(List.of(range()));
        return new PlanningToolContext(
                verified, horizon, registry, new PlanNotebook(horizon), null, List.of());
    }

    private PlanningHorizon.Range range() {
        return new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, 8, 0),
                LocalDateTime.of(2026, 9, 27, 23, 0));
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 120, 0.9);
    }

    private ActivitySessionResponse session(Long sessionId,
                                            Long activityId,
                                            Long venueId,
                                            int startHour,
                                            int endHour) {
        return new ActivitySessionResponse(
                sessionId, activityId, venueId, "venue-" + venueId, "STUDIO", "上海", "浦东", "address",
                BigDecimal.valueOf(31.23), BigDecimal.valueOf(121.47),
                LocalDateTime.of(2026, 9, 27, startHour, 0),
                LocalDateTime.of(2026, 9, 27, endHour, 0),
                BigDecimal.valueOf(100), 10, "OPEN", null, null);
    }
}

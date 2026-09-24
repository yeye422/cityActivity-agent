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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelTimeToolTest {

    @Test
    void shouldResolveOnlyExposedSessionsAndRecordEvidence() {
        AmapTravelTimeService service = mock(AmapTravelTimeService.class);
        PlanningEvidenceRegistry registry = registryWithTwoWindows();
        PlanningToolContext context = context(registry);
        ActivitySessionResponse from = registry.session("AFTERNOON", 101L, 1001L);
        ActivitySessionResponse to = registry.session("EVENING", 202L, 2002L);
        TravelTimeEvidence expected = new TravelTimeEvidence(11L, 22L, 35, "AMAP_DRIVING");
        when(service.resolve(from, to)).thenReturn(expected);

        TravelTimeEvidence actual = new TravelTimeTool(service, null).getTravelTime(
                "AFTERNOON", 101L, 1001L,
                "EVENING", 202L, 2002L,
                context
        );

        assertEquals(expected, actual);
        assertEquals(List.of(expected), registry.travelTimeEvidence());
        assertEquals(1, registry.travelCalls());
        verify(service).resolve(from, to);
    }

    @Test
    void shouldRejectSessionOutsidePlanningEvidence() {
        AmapTravelTimeService service = mock(AmapTravelTimeService.class);
        PlanningEvidenceRegistry registry = registryWithTwoWindows();
        PlanningToolContext context = context(registry);

        assertThrows(CityException.class, () -> new TravelTimeTool(service, null).getTravelTime(
                "AFTERNOON", 101L, 9999L,
                "EVENING", 202L, 2002L,
                context
        ));
        verify(service, never()).resolve(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private PlanningEvidenceRegistry registryWithTwoWindows() {
        ActivityItem afternoonActivity = activity(101L, "陶艺");
        ActivityItem eveningActivity = activity(202L, "小剧场");
        ActivitySessionResponse afternoonSession = session(1001L, 101L, 11L, 31.2304, 121.4737, 14, 0, 16, 0);
        ActivitySessionResponse eveningSession = session(2002L, 202L, 22L, 31.2200, 121.5000, 17, 0, 19, 0);

        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        registry.record(List.of(
                new ActivityPlanService.PlannedActivity(
                        "AFTERNOON", afternoonActivity, SlotBundle.empty(), List.of(afternoonActivity),
                        Map.of(101L, List.of(afternoonSession)), afternoonSession),
                new ActivityPlanService.PlannedActivity(
                        "EVENING", eveningActivity, SlotBundle.empty(), List.of(eveningActivity),
                        Map.of(202L, List.of(eveningSession)), eveningSession)
        ));
        return registry;
    }

    private PlanningToolContext context(PlanningEvidenceRegistry registry) {
        SessionState state = SessionState.fresh("session-plan", 9L, SourceMode.PUBLIC);
        VerifiedRequestContext verified = VerifiedRequestContext.from(
                state, "trace-plan", SemanticContext.empty(), WeatherRecommendationContext.inactive());
        return new PlanningToolContext(verified, List.of("AFTERNOON", "EVENING"), registry,
                new PlanNotebook(List.of("AFTERNOON", "EVENING")), null, List.of());
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 120, 0.9);
    }

    private ActivitySessionResponse session(Long sessionId,
                                            Long activityId,
                                            Long venueId,
                                            double latitude,
                                            double longitude,
                                            int startHour,
                                            int startMinute,
                                            int endHour,
                                            int endMinute) {
        return new ActivitySessionResponse(
                sessionId, activityId, venueId, "venue-" + venueId, "STUDIO", "上海", "浦东", "address",
                BigDecimal.valueOf(latitude), BigDecimal.valueOf(longitude),
                LocalDateTime.of(2026, 9, 26, startHour, startMinute),
                LocalDateTime.of(2026, 9, 26, endHour, endMinute),
                BigDecimal.valueOf(100), 10, "OPEN", null, null
        );
    }
}

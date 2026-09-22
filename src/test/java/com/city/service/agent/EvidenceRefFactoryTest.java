package com.city.service.agent;

import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.TravelTimeEvidence;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.EvidenceRef;
import com.city.model.agent.EvidenceType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvidenceRefFactoryTest {
    private final EvidenceRefFactory factory = new EvidenceRefFactory();

    @Test
    void sessionVenueAndWeatherShouldHaveTypedFingerprints() {
        ActivitySessionResponse session = new ActivitySessionResponse(
                9L, 7L, 3L, "艺术中心", "展馆", "西安", "雁塔", "长安路",
                LocalDateTime.of(2026, 9, 26, 14, 0),
                LocalDateTime.of(2026, 9, 26, 16, 0),
                null, 20, "OPEN", null, null);
        SlotBundle slots = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());

        assertEquals(EvidenceType.ACTIVITY_SESSION, factory.session(session).type());
        assertEquals(EvidenceType.VENUE, factory.venue(session).type());
        assertEquals(EvidenceType.WEATHER,
                factory.weather(WeatherRecommendationContext.indoorPriority("小雨"),
                        slots, TimeConstraint.empty()).type());
        assertFalse(factory.session(session).fingerprint().isBlank());
    }

    @Test
    void travelTimeShouldBecomeTraceableMapRouteEvidence() {
        EvidenceRef route = factory.mapRoute(
                new TravelTimeEvidence(101L, 102L, 30, "AMAP_ROUTE"));

        assertEquals(EvidenceType.MAP_ROUTE, route.type());
        assertEquals("101->102", route.id());
        assertFalse(route.fingerprint().isBlank());
    }
}

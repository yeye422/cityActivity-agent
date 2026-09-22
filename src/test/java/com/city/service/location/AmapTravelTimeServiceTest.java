package com.city.service.location;

import com.city.exception.CityException;
import com.city.model.ActivitySessionResponse;
import com.city.model.TravelTimeEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AmapTravelTimeServiceTest {

    @Test
    void sameVenueShouldReturnZeroWithoutConfiguredApiKey() {
        AmapTravelTimeService service = new AmapTravelTimeService(new ObjectMapper(), "");
        ActivitySessionResponse from = session(1001L, 101L, 11L,
                BigDecimal.valueOf(31.2304), BigDecimal.valueOf(121.4737), 14);
        ActivitySessionResponse to = session(1002L, 202L, 11L,
                BigDecimal.valueOf(31.2304), BigDecimal.valueOf(121.4737), 18);

        TravelTimeEvidence evidence = service.resolve(from, to);

        assertEquals(11L, evidence.fromVenueId());
        assertEquals(11L, evidence.toVenueId());
        assertEquals(0, evidence.durationMinutes());
        assertEquals("AMAP_DRIVING", evidence.source());
    }

    @Test
    void missingCoordinatesShouldFailBeforeRouteLookup() {
        AmapTravelTimeService service = new AmapTravelTimeService(new ObjectMapper(), "test-key");
        ActivitySessionResponse from = new ActivitySessionResponse(
                1001L, 101L, 11L, "A", "STUDIO", "上海", "浦东", "addr",
                LocalDateTime.of(2026, 9, 26, 14, 0),
                LocalDateTime.of(2026, 9, 26, 16, 0),
                BigDecimal.valueOf(100), 10, "OPEN", null, null);
        ActivitySessionResponse to = session(2002L, 202L, 22L,
                BigDecimal.valueOf(31.2200), BigDecimal.valueOf(121.5000), 18);

        assertThrows(CityException.class, () -> service.resolve(from, to));
    }

    @Test
    void invalidCoordinatesShouldBeRejected() {
        AmapTravelTimeService service = new AmapTravelTimeService(new ObjectMapper(), "test-key");
        ActivitySessionResponse from = session(1001L, 101L, 11L,
                BigDecimal.valueOf(95), BigDecimal.valueOf(121.4737), 14);
        ActivitySessionResponse to = session(2002L, 202L, 22L,
                BigDecimal.valueOf(31.2200), BigDecimal.valueOf(121.5000), 18);

        assertThrows(CityException.class, () -> service.resolve(from, to));
    }

    private ActivitySessionResponse session(Long sessionId,
                                            Long activityId,
                                            Long venueId,
                                            BigDecimal latitude,
                                            BigDecimal longitude,
                                            int hour) {
        return new ActivitySessionResponse(
                sessionId, activityId, venueId, "venue-" + venueId, "STUDIO", "上海", "浦东", "addr",
                latitude, longitude,
                LocalDateTime.of(2026, 9, 26, hour, 0),
                LocalDateTime.of(2026, 9, 26, hour + 1, 0),
                BigDecimal.valueOf(100), 10, "OPEN", null, null
        );
    }
}

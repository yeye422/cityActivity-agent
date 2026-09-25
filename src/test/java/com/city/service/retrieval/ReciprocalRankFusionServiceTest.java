package com.city.service.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReciprocalRankFusionServiceTest {
    @Test
    void shouldRewardItemsSupportedByBothRetrievers() {
        Map<Long, Double> scores = new ReciprocalRankFusionService().fuse(
                List.of(1L, 2L, 3L),
                List.of(3L, 1L, 4L)
        );
        assertTrue(scores.get(1L) > scores.get(2L));
        assertTrue(scores.get(3L) > scores.get(4L));
    }
}

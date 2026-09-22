package com.city.service.activity;

import com.city.enums.SourceMode;
import com.city.mapper.ActivitySessionMapper;
import com.city.model.ActivityItem;
import com.city.model.ActivitySearchRequest;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.agent.DiscoveryResult;
import com.city.model.agent.EvidenceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivitySearchServiceTest {
    @Test
    void discoveryShouldReturnVerifiedCandidateWithEvidenceFingerprint() {
        ActivityService activityService = mock(ActivityService.class);
        ActivitySessionMapper sessionMapper = mock(ActivitySessionMapper.class);
        ActivitySearchService service = new ActivitySearchService(activityService, sessionMapper);
        SlotBundle slots = new SlotBundle(
                List.of("西安"), List.of(), List.of(), List.of(), List.of(),
                List.of("展览"), List.of(), List.of(), List.of("室内"));
        ActivityItem item = new ActivityItem(
                7L, SourceMode.PUBLIC, null, "艺术展", slots,
                null, null, null, null, 120, 0.0);
        when(activityService.search(SourceMode.PUBLIC, 1L, slots, TimeConstraint.empty(), SlotBundle.empty()))
                .thenReturn(List.of(item));

        DiscoveryResult result = service.discover(new ActivitySearchRequest(
                SourceMode.PUBLIC, 1L, slots, List.of(), TimeConstraint.empty(), SlotBundle.empty()));

        assertEquals(List.of(item), result.candidates());
        assertEquals(java.util.Set.of(7L), result.agentResult().verifiedActivityIds());
        assertEquals(EvidenceType.ACTIVITY, result.agentResult().evidenceRefs().getFirst().type());
        assertFalse(result.agentResult().evidenceRefs().getFirst().fingerprint().isBlank());
    }
}

package com.city.service.evidence;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import com.city.model.context.PlanningHorizon;
import com.city.service.plan.ActivityPlanService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanningEvidenceRegistryTest {

    @Test
    void shouldLimitDiscoveryAndValidationCalls() {
        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry(1, 3);
        registry.beginDiscovery();
        assertThrows(IllegalStateException.class, registry::beginDiscovery);
        registry.beginValidation();
        registry.beginValidation();
        registry.beginValidation();
        assertThrows(IllegalStateException.class, registry::beginValidation);
        assertEquals(1, registry.discoveryCalls());
        assertEquals(3, registry.validationCalls());
    }

    @Test
    void defaultValidationSafetyBudgetShouldAllowRepeatedRepairsBeyondTwoRounds() {
        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        registry.beginValidation();
        registry.beginValidation();
        registry.beginValidation();
        registry.beginValidation();
        assertEquals(4, registry.validationCalls());
    }

    @Test
    void shouldMergeCandidatesAcrossDiscoveryRanges() {
        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        PlanningHorizon.Range firstRange = range(8, 18);
        PlanningHorizon.Range secondRange = range(15, 18);
        registry.record(List.of(new ActivityPlanService.CandidateBatch(
                firstRange, List.of(activity(101L, "陶艺")), Map.of())));
        registry.record(List.of(new ActivityPlanService.CandidateBatch(
                secondRange, List.of(activity(202L, "咖啡工作坊")), Map.of())));

        assertNotNull(registry.activity(101L));
        assertNotNull(registry.activity(202L));
        assertEquals(List.of(101L, 202L), registry.exposedActivityIds());
        assertEquals(List.of(firstRange, secondRange), registry.searchedRanges());
    }

    private PlanningHorizon.Range range(int startHour, int endHour) {
        return new PlanningHorizon.Range(
                LocalDateTime.of(2026, 9, 27, startHour, 0),
                LocalDateTime.of(2026, 9, 27, endHour, 0));
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 90, 0.5);
    }
}

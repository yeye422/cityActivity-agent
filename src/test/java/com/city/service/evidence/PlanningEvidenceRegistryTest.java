package com.city.service.evidence;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import com.city.service.plan.ActivityPlanService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void shouldMergeExpandedCandidatesIntoExistingPeriod() {
        PlanningEvidenceRegistry registry = new PlanningEvidenceRegistry();
        ActivityItem first = activity(101L, "陶艺");
        ActivityItem expanded = activity(202L, "咖啡工作坊");

        registry.record(List.of(new ActivityPlanService.PlannedActivity(
                "AFTERNOON", null, SlotBundle.empty(), List.of(first), Map.of(), null)));
        registry.record(List.of(new ActivityPlanService.PlannedActivity(
                "AFTERNOON", null, SlotBundle.empty(), List.of(expanded), Map.of(), null)));

        assertNotNull(registry.activity("AFTERNOON", 101L));
        assertNotNull(registry.activity("AFTERNOON", 202L));
        assertEquals(List.of(101L, 202L), registry.exposedActivityIds());
    }

    private ActivityItem activity(Long id, String name) {
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, name, SlotBundle.empty(),
                null, null, null, null, 90, 0.5);
    }
}

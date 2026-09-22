package com.city.service.evidence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

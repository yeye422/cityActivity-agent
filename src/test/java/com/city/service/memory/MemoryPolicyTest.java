package com.city.service.memory;

import com.city.enums.PreferencePolarity;
import com.city.exception.CityException;
import com.city.model.PreferenceFactRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPolicyTest {

    private final MemoryPolicy policy = new MemoryPolicy();

    @Test
    void explicitWriteShouldIgnoreCallerProvidedSource() {
        PreferenceFactRequest normalized = policy.explicit(
                new PreferenceFactRequest("style", "安静", PreferencePolarity.PREFER, "AGENT_INFERRED")
        );

        assertEquals(MemoryPolicy.EXPLICIT, normalized.source());
    }

    @Test
    void confirmedAgentWriteShouldAllowStablePreference() {
        PreferenceFactRequest normalized = policy.confirmedAgentWrite(
                new PreferenceFactRequest("style", "安静", PreferencePolarity.PREFER, "whatever")
        );

        assertEquals(MemoryPolicy.AGENT_CONFIRMED, normalized.source());
        assertTrue(policy.isAgentWritableStableSlot("style"));
    }

    @Test
    void confirmedAgentWriteShouldRejectContextDependentConstraint() {
        assertThrows(CityException.class, () -> policy.confirmedAgentWrite(
                new PreferenceFactRequest("budget", "200以内", PreferencePolarity.PREFER, null)
        ));
        assertThrows(CityException.class, () -> policy.confirmedAgentWrite(
                new PreferenceFactRequest("location", "浦东", PreferencePolarity.PREFER, null)
        ));
    }
}

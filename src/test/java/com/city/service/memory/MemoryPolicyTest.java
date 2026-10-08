package com.city.service.memory;

import com.city.enums.PreferencePolarity;
import com.city.exception.CityException;
import com.city.model.MemoryMutationProposal;
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
    void confirmedAgentWriteShouldAllowExplicitStablePreference() {
        PreferenceFactRequest normalized = policy.confirmedAgentWrite(
                new MemoryMutationProposal("style", "安静", PreferencePolarity.PREFER, true, "以后都喜欢安静")
        );
        assertEquals(MemoryPolicy.AGENT_CONFIRMED, normalized.source());
        assertTrue(policy.isAgentWritableStableSlot("style"));
    }

    @Test
    void confirmedAgentWriteShouldRejectOneOffOrContextDependentConstraint() {
        assertThrows(CityException.class, () -> policy.confirmedAgentWrite(
                new MemoryMutationProposal("style", "安静", PreferencePolarity.PREFER, false, "今天想安静点")
        ));
        assertThrows(CityException.class, () -> policy.confirmedAgentWrite(
                new MemoryMutationProposal("budget", "200元内", PreferencePolarity.PREFER, true, "以后预算200")
        ));
        assertThrows(CityException.class, () -> policy.confirmedAgentWrite(
                new MemoryMutationProposal("companion", "朋友", PreferencePolarity.PREFER, true, "以后和朋友")
        ));
    }
}

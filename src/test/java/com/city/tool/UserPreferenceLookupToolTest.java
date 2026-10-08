package com.city.tool;

import com.city.enums.PreferencePolarity;
import com.city.enums.SourceMode;
import com.city.model.PreferenceFact;
import com.city.model.SessionState;
import com.city.model.WeatherRecommendationContext;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.SemanticContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.evidence.CandidateEvidenceRegistry;
import com.city.service.memory.PreferenceMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserPreferenceLookupToolTest {

    @Test
    void shouldReturnActivePreferencesForCurrentUser() {
        PreferenceMemoryService memory = mock(PreferenceMemoryService.class);
        PreferenceFact fact = new PreferenceFact();
        fact.setSlotName("style");
        fact.setSlotValue("安静");
        fact.setPolarity(PreferencePolarity.PREFER);
        fact.setSource("EXPLICIT");
        fact.setActive(true);
        when(memory.findActive(9L)).thenReturn(List.of(fact));

        UserPreferenceLookupTool tool = new UserPreferenceLookupTool(memory, null);
        var result = tool.lookup("轻松约会", AgentDecisionToolContext.recommendation(
                verified(), new CandidateEvidenceRegistry(1)));

        assertEquals(1, result.preferences().size());
        assertEquals("安静", result.preferences().getFirst().slotValue());
    }

    private VerifiedRequestContext verified() {
        SessionState state = SessionState.fresh("session-pref", 9L, SourceMode.PUBLIC);
        return VerifiedRequestContext.from(
                state, "trace-pref", SemanticContext.empty(),
                WeatherRecommendationContext.inactive());
    }
}

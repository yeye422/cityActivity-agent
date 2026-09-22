package com.city.service.memory;

import com.city.enums.PreferencePolarity;
import com.city.exception.CityException;
import com.city.mapper.PreferenceFactMapper;
import com.city.model.PreferenceFact;
import com.city.model.PreferenceFactRequest;
import com.city.service.slot.SlotOptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreferenceMemoryServiceTest {
    private final PreferenceFactMapper mapper = mock(PreferenceFactMapper.class);
    private final SlotOptionService slotOptionService = mock(SlotOptionService.class);
    private final PreferenceMemoryService service = new PreferenceMemoryService(mapper, slotOptionService);

    @Test
    void rememberShouldValidateDictionaryAndUpsertNaturalKey() {
        when(slotOptionService.findAllOptions()).thenReturn(Map.of("style", List.of("安静")));
        PreferenceFact stored = new PreferenceFact();
        stored.setId(7L);
        stored.setSlotName("style");
        stored.setSlotValue("安静");
        stored.setPolarity(PreferencePolarity.PREFER);
        when(mapper.findByNaturalKey(1L, "style", "安静", PreferencePolarity.PREFER)).thenReturn(stored);

        PreferenceFact result = service.remember(1L,
                new PreferenceFactRequest("style", "安静", PreferencePolarity.PREFER, null));

        assertEquals(7L, result.getId());
        verify(mapper).upsert(1L, "style", "安静", PreferencePolarity.PREFER, "EXPLICIT");
    }

    @Test
    void explicitRememberShouldNotTrustCallerProvidedSource() {
        when(slotOptionService.findAllOptions()).thenReturn(Map.of("style", List.of("安静")));
        when(mapper.findByNaturalKey(1L, "style", "安静", PreferencePolarity.PREFER))
                .thenReturn(new PreferenceFact());

        service.remember(1L,
                new PreferenceFactRequest("style", "安静", PreferencePolarity.PREFER, "AGENT_INFERRED"));

        verify(mapper).upsert(1L, "style", "安静", PreferencePolarity.PREFER, MemoryPolicy.EXPLICIT);
    }

    @Test
    void confirmedAgentPreferenceShouldAllowStableSlot() {
        when(slotOptionService.findAllOptions()).thenReturn(Map.of("style", List.of("安静")));
        when(mapper.findByNaturalKey(1L, "style", "安静", PreferencePolarity.PREFER))
                .thenReturn(new PreferenceFact());

        service.rememberConfirmedAgentPreference(1L,
                new PreferenceFactRequest("style", "安静", PreferencePolarity.PREFER, null));

        verify(mapper).upsert(1L, "style", "安静", PreferencePolarity.PREFER, MemoryPolicy.AGENT_CONFIRMED);
    }

    @Test
    void confirmedAgentPreferenceShouldRejectBudgetBeforePersistence() {
        assertThrows(CityException.class, () -> service.rememberConfirmedAgentPreference(1L,
                new PreferenceFactRequest("budget", "200以内", PreferencePolarity.PREFER, null)));

        verify(mapper, never()).upsert(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void forgetShouldUseOptimisticVersion() {
        when(mapper.softDelete(1L, 7L, 2)).thenReturn(0);
        assertThrows(CityException.class, () -> service.forget(1L, 7L, 2));
    }
}

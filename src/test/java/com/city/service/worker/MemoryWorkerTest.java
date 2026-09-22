package com.city.service.worker;

import com.city.enums.PreferencePolarity;
import com.city.model.PreferenceFactRequest;
import com.city.service.memory.PreferenceMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryWorkerTest {

    @Test
    void shouldDelegateOnlyExplicitMemoryOperations() {
        PreferenceMemoryService service = mock(PreferenceMemoryService.class);
        MemoryWorker worker = new MemoryWorker(service);
        PreferenceFactRequest request = new PreferenceFactRequest(
                "activityType", "展览", PreferencePolarity.POSITIVE, "EXPLICIT");
        when(service.findActive(1L)).thenReturn(List.of());

        assertEquals(List.of(), worker.findActive(1L));
        worker.remember(1L, request);
        worker.forget(1L, 10L, 2);

        verify(service).findActive(1L);
        verify(service).remember(1L, request);
        verify(service).forget(1L, 10L, 2);
    }
}

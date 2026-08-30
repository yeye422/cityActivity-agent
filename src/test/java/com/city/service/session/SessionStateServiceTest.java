package com.city.service.session;

import com.city.enums.SourceMode;
import com.city.mapper.SessionMapper;
import com.city.model.SessionRow;
import com.city.model.SessionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionStateServiceTest {

    @Test
    void loadExistingSessionShouldUseCurrentRequestSourceMode() {
        SessionMapper mapper = mock(SessionMapper.class);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        SessionStateService service = new SessionStateService(mapper, objectMapper);

        SessionRow row = new SessionRow();
        row.setId("sess_test");
        row.setUserId(1L);
        row.setPhase("RECOMMEND");
        row.setSlots("""
                {
                  "city":["西安"],
                  "_meta":{
                    "sourceMode":"PUBLIC",
                    "currentIntent":"MEAL_RECOMMENDATION",
                    "recommendationQueryKey":"old-key"
                  }
                }
                """);
        row.setLastRecommendedActivityIds("[]");
        when(mapper.findById("sess_test", 1L)).thenReturn(row);

        SessionState state = service.loadOrCreate("sess_test", 1L, SourceMode.PERSONAL);

        assertEquals(SourceMode.PERSONAL, state.sourceMode());
        assertEquals("old-key", state.recommendationQueryKey());
    }

    @Test
    void saveShouldNotPersistSourceModeAsSessionAuthority() throws Exception {
        SessionMapper mapper = mock(SessionMapper.class);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        SessionStateService service = new SessionStateService(mapper, objectMapper);
        when(mapper.update(any(SessionRow.class))).thenReturn(1);

        service.save(SessionState.fresh("sess_test", 1L, SourceMode.PERSONAL));

        ArgumentCaptor<SessionRow> captor = ArgumentCaptor.forClass(SessionRow.class);
        verify(mapper).update(captor.capture());
        JsonNode persisted = objectMapper.readTree(captor.getValue().getSlots());
        assertFalse(persisted.path("_meta").has("sourceMode"));
    }
}

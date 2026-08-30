package com.city.service.session;

import com.city.enums.SourceMode;
import com.city.mapper.SessionMapper;
import com.city.model.SessionRow;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void saveShouldPersistSourceModeAndFeature() throws Exception {
        SessionMapper mapper = mock(SessionMapper.class);
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        SessionStateService service = new SessionStateService(mapper, objectMapper);
        when(mapper.update(any(SessionRow.class))).thenReturn(1);

        SlotBundle slots = new SlotBundle(
                List.of("西安"),
                List.of("曲江"),
                List.of("解压"),
                List.of("独处"),
                List.of("100元内"),
                List.of("展览"),
                List.of("安静"),
                List.of("2-4小时"),
                List.of("室内", "近地铁")
        );
        service.save(SessionState.fresh("sess_test", 1L, SourceMode.PERSONAL).withSlots(slots));

        ArgumentCaptor<SessionRow> captor = ArgumentCaptor.forClass(SessionRow.class);
        verify(mapper).update(captor.capture());
        JsonNode persisted = objectMapper.readTree(captor.getValue().getSlots());
        assertEquals("PERSONAL", persisted.path("_meta").path("sourceMode").asText());
        assertEquals("室内", persisted.path("feature").get(0).asText());
        assertEquals("近地铁", persisted.path("feature").get(1).asText());
        assertEquals("解压", persisted.path("experienceGoal").get(0).asText());
        assertEquals("独处", persisted.path("companion").get(0).asText());
    }

}

package com.city.service.history;

import com.city.enums.SourceMode;
import com.city.mapper.SessionMapper;
import com.city.model.ActivityItem;
import com.city.model.SessionRow;
import com.city.model.SlotBundle;
import com.city.service.activity.ActivityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecentActivityHistoryServiceTest {

    @Test
    void shouldAggregateCrossSessionHistoryWithoutDuplicates() {
        SessionMapper mapper = mock(SessionMapper.class);
        ActivityService activityService = mock(ActivityService.class);

        SessionRow latest = session("[101,102]");
        SessionRow older = session("[102,103]");
        when(mapper.listRecentRecommendationSessions(9L, 12))
                .thenReturn(List.of(latest, older));
        when(activityService.findPublicActivities())
                .thenReturn(List.of(activity(101L), activity(102L), activity(103L)));
        when(activityService.findPersonalActivities(9L)).thenReturn(List.of());

        RecentActivityHistoryService service = new RecentActivityHistoryService(
                mapper, activityService, new ObjectMapper());

        var result = service.findRecent(9L, 3);
        assertEquals(List.of(102L, 101L, 103L),
                result.items().stream().map(item -> item.activityId()).toList());
    }

    private SessionRow session(String recommendations) {
        SessionRow row = new SessionRow();
        row.setLastRecommendedActivityIds(recommendations);
        return row;
    }

    private ActivityItem activity(Long id) {
        return new ActivityItem(
                id, SourceMode.PUBLIC, null, "activity-" + id, SlotBundle.empty(),
                null, null, null, null, 90, 0.5);
    }
}

package com.city.service.retrieval;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivityVectorRetrieverTest {
    @Test
    void shouldRankSemanticallyCloserCandidateHigher() {
        ActivityEmbeddingService embeddingService = mock(ActivityEmbeddingService.class);
        ActivityItem close = new ActivityItem(1L, SourceMode.PUBLIC, null, "陶艺", "安静治愈的陶艺手作",
                SlotBundle.empty(), null, null, null, null, 120, 0.0);
        ActivityItem far = new ActivityItem(2L, SourceMode.PUBLIC, null, "篮球", "高强度篮球对抗",
                SlotBundle.empty(), null, null, null, null, 120, 0.0);

        when(embeddingService.available()).thenReturn(true);
        when(embeddingService.embedQuery("想安静放空")).thenReturn(List.of(1.0, 0.0));
        when(embeddingService.vectorsFor(List.of(close, far))).thenReturn(Map.of(
                1L, List.of(0.95, 0.05),
                2L, List.of(0.0, 1.0)
        ));

        Map<Long, Double> scores = new ActivityVectorRetriever(embeddingService)
                .score(List.of(close, far), "想安静放空");

        assertTrue(scores.get(1L) > scores.get(2L));
    }
}

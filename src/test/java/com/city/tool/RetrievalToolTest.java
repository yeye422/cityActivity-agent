package com.city.tool;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.context.SemanticContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.retrieval.RetrievalRequest;
import com.city.model.retrieval.RetrievalResult;
import com.city.model.tool.RetrievalToolResult;
import com.city.service.context.SemanticContextBuilder;
import com.city.service.retrieval.RetrievalPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalToolTest {

    @Mock
    private RetrievalPipeline retrievalPipeline;

    @Test
    void shouldKeepHardConstraintsServerControlledAndExposeOnlySoftIntent() {
        SlotBundle slots = new SlotBundle(
                List.of("上海"), List.of("浦东"), List.of("新鲜"), List.of("情侣"),
                List.of("200元内"), List.of("手作"), List.of("安静"), List.of(), List.of("室内")
        );
        SlotBundle excluded = new SlotBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("展览"), List.of(), List.of(), List.of()
        );
        SessionState state = SessionState.fresh("session-1", 9L, SourceMode.PUBLIC)
                .withSlots(slots)
                .withExcludedSlots(excluded)
                .withLastRecommendations(List.of(88L));
        SemanticContext semantic = new SemanticContextBuilder().build(state);
        VerifiedRequestContext verified = VerifiedRequestContext.from(
                state,
                "trace-1",
                semantic,
                WeatherRecommendationContext.inactive()
        );

        ActivityItem candidate = new ActivityItem(
                101L,
                SourceMode.PUBLIC,
                null,
                "双人陶艺",
                slots,
                null,
                null,
                null,
                null,
                120,
                0.92
        );
        when(retrievalPipeline.retrieve(any(RetrievalRequest.class)))
                .thenReturn(new RetrievalResult(
                        List.of(candidate), List.of(candidate), List.of(candidate), List.of(), List.of()));

        RetrievalTool tool = new RetrievalTool(retrievalPipeline);
        RetrievalToolResult result = tool.searchActivities("互动、有新鲜感的约会体验", verified);

        assertEquals(List.of(101L), result.candidates().stream().map(RetrievalToolResult.Candidate::activityId).toList());

        ArgumentCaptor<RetrievalRequest> captor = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(retrievalPipeline).retrieve(captor.capture());
        RetrievalRequest forwarded = captor.getValue();

        assertEquals("互动、有新鲜感的约会体验", forwarded.queryText());
        assertEquals(SourceMode.PUBLIC, forwarded.searchRequest().sourceMode());
        assertEquals(9L, forwarded.searchRequest().userId());
        assertEquals(List.of("上海"), forwarded.searchRequest().slots().city());
        assertEquals(List.of("200元内"), forwarded.searchRequest().slots().budget());
        assertEquals(List.of("展览"), forwarded.searchRequest().excludedSlots().activityType());
        assertEquals(List.of(88L), forwarded.searchRequest().excludeActivityIds());
        assertEquals(TimeConstraint.empty(), forwarded.searchRequest().timeConstraint());
    }
}

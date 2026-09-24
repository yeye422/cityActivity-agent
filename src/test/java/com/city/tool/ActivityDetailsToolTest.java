package com.city.tool;

import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.model.ActivityItem;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.context.SemanticContext;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.tool.RetrievalToolResult;
import com.city.service.evidence.CandidateEvidenceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityDetailsToolTest {

    @Test
    void shouldInspectOnlyCurrentRunExposedCandidates() {
        ActivityItem item = new ActivityItem(
                101L, SourceMode.PUBLIC, null, "陶艺", SlotBundle.empty(),
                null, null, null, null, 120, 0.9);
        CandidateEvidenceRegistry registry = new CandidateEvidenceRegistry(1);
        registry.beginRetrieval("互动");
        registry.recordResult(RetrievalToolResult.from("互动", List.of(item)), List.of(item));

        AgentDecisionToolContext context = AgentDecisionToolContext.recommendation(
                verified(), registry);
        ActivityDetailsTool tool = new ActivityDetailsTool(null);

        var result = tool.inspect(List.of(101L), context);
        assertEquals(1, result.activities().size());
        assertEquals(101L, result.activities().getFirst().activityId());

        assertThrows(CityException.class, () -> tool.inspect(List.of(999L), context));
    }

    private VerifiedRequestContext verified() {
        SessionState state = SessionState.fresh("session-details", 9L, SourceMode.PUBLIC);
        return VerifiedRequestContext.from(
                state, "trace-details", SemanticContext.empty(),
                WeatherRecommendationContext.inactive());
    }
}

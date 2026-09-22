package com.city.service.recommend;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.RecommendationDecision;
import com.city.model.agent.RecommendationExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecommendationResponseGeneratorServiceTest {

    @Mock
    private RecommendResponseAgentService legacyResponseService;

    @Test
    void shouldPassOnlyAgentSelectedVerifiedActivitiesToResponseLayer() {
        RecommendationResponseGeneratorService service =
                new RecommendationResponseGeneratorService(legacyResponseService);
        SlotBundle slots = SlotBundle.empty();
        ActivityItem selected = new ActivityItem(
                101L, SourceMode.PUBLIC, null, "陶艺", slots,
                null, null, null, null, 120, 0.9);
        RecommendationDecision decision = new RecommendationDecision(
                List.of(101L),
                List.of(new RecommendationDecision.CandidateAssessment(
                        101L, "HIGH", "互动性强")),
                true,
                "优先互动",
                0.9
        );
        RecommendationExecutionResult execution = new RecommendationExecutionResult(
                decision,
                List.of(selected),
                1,
                List.of()
        );
        WeatherRecommendationContext weather = WeatherRecommendationContext.inactive();

        service.generate("s1", "想约会", SourceMode.PUBLIC, slots, execution, weather);

        verify(legacyResponseService).recommendAndRespond(
                "s1", "想约会", SourceMode.PUBLIC, slots, List.of(selected), weather);
    }
}

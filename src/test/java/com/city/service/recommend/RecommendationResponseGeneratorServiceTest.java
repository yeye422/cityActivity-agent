package com.city.service.recommend;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.RecommendationDecision;
import com.city.model.agent.RecommendationExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationResponseGeneratorServiceTest {

    @Test
    void shouldBuildResponseDirectlyFromVerifiedSelectionAndDecisionReason() {
        RecommendationResponseGeneratorService service = new RecommendationResponseGeneratorService();
        SlotBundle slots = SlotBundle.empty();
        ActivityItem selected = new ActivityItem(
                101L, SourceMode.PUBLIC, null, "陶艺", slots,
                null, null, null, null, 120, 0.9);
        RecommendationDecision decision = new RecommendationDecision(
                List.of(101L),
                List.of(new RecommendationDecision.CandidateAssessment(
                        101L, "HIGH", "互动性强，比较符合约会和新鲜感目标")),
                true,
                "优先选择互动且有参与感的体验",
                0.9
        );
        RecommendationExecutionResult execution = new RecommendationExecutionResult(
                decision,
                List.of(selected),
                1,
                List.of()
        );

        RecommendResponseAgentService.Result result = service.generate(
                "s1", "想约会", SourceMode.PUBLIC, slots, execution,
                WeatherRecommendationContext.inactive()
        );

        assertEquals(1, result.recommend().recommendations().size());
        assertEquals(101L, result.recommend().recommendations().getFirst().itemId());
        assertEquals("互动性强，比较符合约会和新鲜感目标",
                result.recommend().recommendations().getFirst().reason());
        assertEquals(1, result.response().displayBlocks().size());
        assertEquals(101L, result.response().displayBlocks().getFirst().id());
        assertTrue(result.response().speechText().contains("优先选择互动且有参与感的体验"));
        assertTrue(result.response().speechText().contains("陶艺"));
    }

    @Test
    void shouldUseDeterministicFallbackReasonWhenAssessmentMissing() {
        RecommendationResponseGeneratorService service = new RecommendationResponseGeneratorService();
        ActivityItem selected = new ActivityItem(
                102L, SourceMode.PUBLIC, null, "银饰制作", SlotBundle.empty(),
                null, null, null, null, 90, 0.8);
        RecommendationDecision decision = new RecommendationDecision(
                List.of(102L), List.of(), true, "更看重纪念感和互动性", 0.8);
        RecommendationExecutionResult execution = new RecommendationExecutionResult(
                decision, List.of(selected), 1, List.of());

        RecommendResponseAgentService.Result result = service.generate(
                "s1", "想特别一点", SourceMode.PUBLIC, SlotBundle.empty(), execution,
                WeatherRecommendationContext.inactive());

        assertTrue(result.recommend().recommendations().getFirst().reason().contains("更看重纪念感和互动性"));
    }
}

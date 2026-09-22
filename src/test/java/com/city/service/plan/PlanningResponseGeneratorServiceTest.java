package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.PlanCandidate;
import com.city.model.RecommendResult;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanProposal;
import com.city.model.agent.PlanValidationResult;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.agent.PlanningDecision;
import com.city.service.recommend.RecommendResponseAgentService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningResponseGeneratorServiceTest {

    @Test
    void shouldBuildResponseDirectlyFromSolverAcceptedPlan() {
        PlanningResponseGeneratorService service = new PlanningResponseGeneratorService();
        ActivityItem activity = new ActivityItem(
                201L, SourceMode.PUBLIC, null, "双人陶艺", SlotBundle.empty(),
                null, null, null, null, 120, 0.91);
        PlanProposal proposal = new PlanProposal(List.of(
                new PlanProposal.Item("14:00-16:00", 201L, null)
        ));
        PlanningDecision decision = new PlanningDecision(
                proposal,
                "下午先安排互动体验，整体节奏保持轻松",
                0.9
        );
        PlanCandidate accepted = new PlanCandidate(
                List.of(new PlanCandidate.Item("14:00-16:00", activity, null)),
                BigDecimal.ZERO
        );
        PlanningAgentExecutionResult execution = new PlanningAgentExecutionResult(
                decision,
                accepted,
                PlanValidationResult.valid(accepted)
        );

        RecommendResponseAgentService.Result result = service.generate(
                "s1",
                "帮我安排下午",
                SourceMode.PUBLIC,
                SlotBundle.empty(),
                execution,
                WeatherRecommendationContext.inactive()
        );

        RecommendResult recommend = result.recommend();
        assertEquals(1, recommend.recommendations().size());
        assertEquals(201L, recommend.recommendations().getFirst().itemId());
        assertTrue(recommend.recommendations().getFirst().reason().contains("规划硬约束"));
        assertEquals(1, result.response().displayBlocks().size());
        assertEquals(201L, result.response().displayBlocks().getFirst().id());
        assertTrue(result.response().speechText().contains("下午先安排互动体验"));
        assertTrue(result.response().speechText().contains("双人陶艺"));
    }
}

package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import com.city.model.context.PlanningToolContext;
import com.city.service.harness.AgentBusinessGuardMiddleware;
import com.city.service.trace.AgentTraceService;
import com.city.tool.ActivityDetailsTool;
import com.city.tool.PlanningCandidateExpansionTool;
import com.city.tool.RecentActivityHistoryTool;
import com.city.tool.UserPreferenceLookupTool;
import com.city.tool.TravelTimeTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/**
 * 构建单次 PlanningAgent。
 *
 * <p>同一个 Agent 负责按需调用探索 Tool、提交 PlanningDecision，并在 Java validate_plan
 * 返回 violations 后继续修复。Java 不替 Agent 选择 activity/session。</p>
 */
@Component
public class PlanningAgentBuilder {

    private final Model mainModel;
    private final PromptLoader promptLoader;
    private final PlanningCandidateExpansionTool expansionTool;
    private final ActivityDetailsTool activityDetailsTool;
    private final UserPreferenceLookupTool preferenceLookupTool;
    private final RecentActivityHistoryTool recentHistoryTool;
    private final TravelTimeTool travelTimeTool;
    private final AgentTraceService traceService;

    public PlanningAgentBuilder(
            @Qualifier("CityMainChatModel") Model mainModel,
            PromptLoader promptLoader,
            PlanningCandidateExpansionTool expansionTool,
            ActivityDetailsTool activityDetailsTool,
            UserPreferenceLookupTool preferenceLookupTool,
            RecentActivityHistoryTool recentHistoryTool,
            TravelTimeTool travelTimeTool,
            AgentTraceService traceService
    ) {
        this.mainModel = Objects.requireNonNull(mainModel, "mainModel");
        this.promptLoader = Objects.requireNonNull(promptLoader, "promptLoader");
        this.expansionTool = Objects.requireNonNull(expansionTool, "expansionTool");
        this.activityDetailsTool = Objects.requireNonNull(activityDetailsTool, "activityDetailsTool");
        this.preferenceLookupTool = Objects.requireNonNull(preferenceLookupTool, "preferenceLookupTool");
        this.recentHistoryTool = Objects.requireNonNull(recentHistoryTool, "recentHistoryTool");
        this.travelTimeTool = Objects.requireNonNull(travelTimeTool, "travelTimeTool");
        this.traceService = traceService;
    }

    public ReActAgent build(PlanningToolContext planningContext) {
        Objects.requireNonNull(planningContext, "planningContext");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(expansionTool);
        toolkit.registerTool(activityDetailsTool);
        toolkit.registerTool(preferenceLookupTool);
        toolkit.registerTool(recentHistoryTool);
        toolkit.registerTool(travelTimeTool);

        AgentBusinessGuardMiddleware guard = new AgentBusinessGuardMiddleware(
                "city_planning_agent",
                Set.of(
                        "expand_plan_candidates",
                        "inspect_activity_details",
                        "lookup_user_preferences",
                        "lookup_recent_activity_history",
                        "get_travel_time"
                ),
                8,
                3,
                traceService,
                planningContext.verifiedRequestContext().traceId()
        );

        return ReActAgent.builder()
                .name("city_planning_agent")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/planning-decision.txt"))
                .toolkit(toolkit)
                .middleware(guard)
                .maxIters(8)
                .build();
    }
}

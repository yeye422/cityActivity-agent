package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import com.city.model.context.PlanningToolContext;
import com.city.service.harness.AgentBusinessGuardMiddleware;
import com.city.service.trace.AgentTraceService;
import com.city.tool.ActivityDetailsTool;
import com.city.tool.PlanningCandidateExpansionTool;
import com.city.tool.RecentActivityHistoryTool;
import com.city.tool.UserPreferenceLookupTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/**
 * Planning 使用两阶段 Agent：
 * Explorer 按需调用语义探索 Tool；Finalizer 无 Tool，只负责结构化 PlanningDecision。
 */
@Component
public class PlanningAgentBuilder {

    private final Model mainModel;
    private final PromptLoader promptLoader;
    private final PlanningCandidateExpansionTool expansionTool;
    private final ActivityDetailsTool activityDetailsTool;
    private final UserPreferenceLookupTool preferenceLookupTool;
    private final RecentActivityHistoryTool recentHistoryTool;
    private final AgentTraceService traceService;

    public PlanningAgentBuilder(
            @Qualifier("CityMainChatModel") Model mainModel,
            PromptLoader promptLoader,
            PlanningCandidateExpansionTool expansionTool,
            ActivityDetailsTool activityDetailsTool,
            UserPreferenceLookupTool preferenceLookupTool,
            RecentActivityHistoryTool recentHistoryTool,
            AgentTraceService traceService
    ) {
        this.mainModel = Objects.requireNonNull(mainModel, "mainModel");
        this.promptLoader = Objects.requireNonNull(promptLoader, "promptLoader");
        this.expansionTool = Objects.requireNonNull(expansionTool, "expansionTool");
        this.activityDetailsTool = Objects.requireNonNull(activityDetailsTool, "activityDetailsTool");
        this.preferenceLookupTool = Objects.requireNonNull(preferenceLookupTool, "preferenceLookupTool");
        this.recentHistoryTool = Objects.requireNonNull(recentHistoryTool, "recentHistoryTool");
        this.traceService = traceService;
    }

    public ReActAgent buildExplorer(PlanningToolContext planningContext) {
        Objects.requireNonNull(planningContext, "planningContext");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(expansionTool);
        toolkit.registerTool(activityDetailsTool);
        toolkit.registerTool(preferenceLookupTool);
        toolkit.registerTool(recentHistoryTool);

        AgentBusinessGuardMiddleware guard = new AgentBusinessGuardMiddleware(
                "city_planning_explorer",
                Set.of(
                        "expand_plan_candidates",
                        "inspect_activity_details",
                        "lookup_user_preferences",
                        "lookup_recent_activity_history"
                ),
                4,
                3,
                traceService,
                planningContext.verifiedRequestContext().traceId()
        );

        return ReActAgent.builder()
                .name("city_planning_explorer")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/planning-exploration.txt"))
                .toolkit(toolkit)
                .middleware(guard)
                .maxIters(6)
                .build();
    }

    public ReActAgent buildFinalizer() {
        return ReActAgent.builder()
                .name("city_planning_finalizer")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/planning-decision.txt"))
                .maxIters(3)
                .build();
    }
}

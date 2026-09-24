package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import com.city.model.context.VerifiedRequestContext;
import com.city.service.harness.AgentBusinessGuardMiddleware;
import com.city.service.trace.AgentTraceService;
import com.city.tool.ActivityDetailsTool;
import com.city.tool.RecentActivityHistoryTool;
import com.city.tool.RetrievalTool;
import com.city.tool.UserPreferenceLookupTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** 构建单次执行使用的 RecommendationAgent。 */
@Component
public class RecommendationAgentBuilder {
    private final Model mainModel;
    private final PromptLoader promptLoader;
    private final RetrievalTool retrievalTool;
    private final ActivityDetailsTool activityDetailsTool;
    private final UserPreferenceLookupTool preferenceLookupTool;
    private final RecentActivityHistoryTool recentHistoryTool;
    private final AgentTraceService traceService;

    public RecommendationAgentBuilder(
            @Qualifier("CityMainChatModel") Model mainModel,
            PromptLoader promptLoader,
            RetrievalTool retrievalTool,
            ActivityDetailsTool activityDetailsTool,
            UserPreferenceLookupTool preferenceLookupTool,
            RecentActivityHistoryTool recentHistoryTool,
            AgentTraceService traceService
    ) {
        this.mainModel = Objects.requireNonNull(mainModel, "mainModel");
        this.promptLoader = Objects.requireNonNull(promptLoader, "promptLoader");
        this.retrievalTool = Objects.requireNonNull(retrievalTool, "retrievalTool");
        this.activityDetailsTool = Objects.requireNonNull(activityDetailsTool, "activityDetailsTool");
        this.preferenceLookupTool = Objects.requireNonNull(preferenceLookupTool, "preferenceLookupTool");
        this.recentHistoryTool = Objects.requireNonNull(recentHistoryTool, "recentHistoryTool");
        this.traceService = traceService;
    }

    public ReActAgent build(VerifiedRequestContext verifiedContext) {
        Objects.requireNonNull(verifiedContext, "verifiedContext");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(retrievalTool);
        toolkit.registerTool(activityDetailsTool);
        toolkit.registerTool(preferenceLookupTool);
        toolkit.registerTool(recentHistoryTool);

        AgentBusinessGuardMiddleware guardMiddleware = new AgentBusinessGuardMiddleware(
                "city_recommendation_agent",
                Set.of(
                        "search_activities",
                        "inspect_activity_details",
                        "lookup_user_preferences",
                        "lookup_recent_activity_history"
                ),
                4,
                3,
                traceService,
                verifiedContext.traceId()
        );

        return ReActAgent.builder()
                .name("city_recommendation_agent")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/recommendation-decision.txt"))
                .toolkit(toolkit)
                .middleware(guardMiddleware)
                .maxIters(6)
                .build();
    }
}

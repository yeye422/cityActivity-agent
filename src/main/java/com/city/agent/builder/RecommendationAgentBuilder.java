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

/**
 * Recommendation 使用两阶段 Agent：
 * Explorer 按需检索/补充信息；Finalizer 无 Tool，只负责结构化 RecommendationDecision。
 */
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

    public ReActAgent buildExplorer(VerifiedRequestContext verifiedContext) {
        Objects.requireNonNull(verifiedContext, "verifiedContext");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(retrievalTool);
        toolkit.registerTool(activityDetailsTool);
        toolkit.registerTool(preferenceLookupTool);
        toolkit.registerTool(recentHistoryTool);

        AgentBusinessGuardMiddleware guard = new AgentBusinessGuardMiddleware(
                "city_recommendation_explorer",
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
                .name("city_recommendation_explorer")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/recommendation-exploration.txt"))
                .toolkit(toolkit)
                .middleware(guard)
                .maxIters(6)
                .build();
    }

    public ReActAgent buildFinalizer() {
        return ReActAgent.builder()
                .name("city_recommendation_finalizer")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/recommendation-decision.txt"))
                .maxIters(3)
                .build();
    }
}

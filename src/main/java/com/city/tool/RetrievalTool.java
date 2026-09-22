package com.city.tool;

import com.city.model.ActivitySearchRequest;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.retrieval.RetrievalRequest;
import com.city.model.retrieval.RetrievalResult;
import com.city.model.tool.RetrievalToolResult;
import com.city.service.retrieval.RetrievalPipeline;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Objects;

/**
 * RecommendationAgent / PlanningAgent 共用的受控活动检索 Tool。
 *
 * <p>模型只能提供 retrievalIntent；用户、数据源、预算、时间、排除项等硬条件来自
 * VerifiedRequestContext，由 AgentScope RuntimeContext 按类型注入，因此不会进入 Tool JSON Schema。</p>
 */
@Component
public class RetrievalTool {
    private static final int DEFAULT_TOP_K = 8;

    private final RetrievalPipeline retrievalPipeline;

    public RetrievalTool(RetrievalPipeline retrievalPipeline) {
        this.retrievalPipeline = Objects.requireNonNull(retrievalPipeline, "retrievalPipeline");
    }

    @Tool(
            name = "search_activities",
            description = "Search legal activity candidates under server-verified hard constraints. "
                    + "Only describe the soft retrieval goal; city, time, budget and exclusions are injected by the server."
    )
    public RetrievalToolResult searchActivities(
            @ToolParam(
                    name = "retrievalIntent",
                    description = "Soft semantic search goal, e.g. interactive, novel and suitable for a date"
            ) String retrievalIntent,
            VerifiedRequestContext verifiedContext
    ) {
        Objects.requireNonNull(verifiedContext, "verifiedContext");
        String safeIntent = retrievalIntent == null ? "" : retrievalIntent.trim();

        ActivitySearchRequest searchRequest = new ActivitySearchRequest(
                verifiedContext.sourceMode(),
                verifiedContext.userId(),
                verifiedContext.effectiveSlots(),
                new ArrayList<>(verifiedContext.hardConstraints().excludedActivityIds()),
                verifiedContext.hardConstraints().timeConstraint(),
                verifiedContext.hardConstraints().excludedSlots()
        );

        RetrievalResult result = retrievalPipeline.retrieve(new RetrievalRequest(
                searchRequest,
                safeIntent,
                verifiedContext.weather(),
                DEFAULT_TOP_K
        ));
        return RetrievalToolResult.from(safeIntent, result.finalCandidates());
    }
}

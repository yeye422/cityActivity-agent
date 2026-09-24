package com.city.tool;

import com.city.model.context.AgentDecisionToolContext;
import com.city.model.tool.RecentActivityHistoryToolResult;
import com.city.service.history.RecentActivityHistoryService;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Recommendation / Planning 共用的跨会话近期推荐历史 Tool。 */
@Component
public class RecentActivityHistoryTool {
    private final RecentActivityHistoryService historyService;
    private final AgentTraceService traceService;

    public RecentActivityHistoryTool(RecentActivityHistoryService historyService,
                                     AgentTraceService traceService) {
        this.historyService = Objects.requireNonNull(historyService, "historyService");
        this.traceService = traceService;
    }

    @Tool(
            name = "lookup_recent_activity_history",
            description = "Read recent cross-session recommended activities to avoid repetition and improve novelty."
    )
    public RecentActivityHistoryToolResult lookup(
            @ToolParam(name = "maxItems", description = "Maximum recent activities to inspect, between 1 and 12")
            Integer maxItems,
            AgentDecisionToolContext context
    ) {
        Objects.requireNonNull(context, "context");
        int safeLimit = maxItems == null ? 8 : Math.max(1, Math.min(12, maxItems));
        RecentActivityHistoryToolResult result = historyService.findRecent(
                context.verifiedRequestContext().userId(),
                safeLimit
        );
        if (traceService != null) {
            traceService.recordEventForTrace(
                    context.verifiedRequestContext().traceId(),
                    "RECENT_ACTIVITY_HISTORY_TOOL_CALLED",
                    "TOOL",
                    Map.of("maxItems", safeLimit),
                    result
            );
        }
        return result;
    }
}

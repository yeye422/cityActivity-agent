package com.city.tool;

import com.city.exception.CityException;
import com.city.model.context.AgentDecisionToolContext;
import com.city.model.tool.ActivityDetailsToolResult;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Recommendation / Planning 共用的按需候选详情 Tool。 */
@Component
public class ActivityDetailsTool {
    private final AgentTraceService traceService;

    public ActivityDetailsTool(AgentTraceService traceService) {
        this.traceService = traceService;
    }

    @Tool(
            name = "inspect_activity_details",
            description = "Inspect richer verified details for activity IDs already exposed in the current agent run."
    )
    public ActivityDetailsToolResult inspect(
            @ToolParam(name = "activityIds", description = "Activity IDs already present in current candidates")
            List<Long> activityIds,
            AgentDecisionToolContext context
    ) {
        Objects.requireNonNull(context, "context");
        List<Long> safeIds = activityIds == null ? List.of() : activityIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(6)
                .toList();
        if (safeIds.isEmpty()) return new ActivityDetailsToolResult(List.of());
        if (!context.exposedActivityIds().containsAll(safeIds)) {
            throw new CityException("只能查看当前 Run 已暴露候选的活动详情");
        }

        ActivityDetailsToolResult result = ActivityDetailsToolResult.from(context.resolveActivities(safeIds));
        trace("ACTIVITY_DETAILS_TOOL_CALLED", context, Map.of("activityIds", safeIds), result);
        return result;
    }

    private void trace(String type, AgentDecisionToolContext context, Object input, Object output) {
        if (traceService == null) return;
        traceService.recordEventForTrace(
                context.verifiedRequestContext().traceId(),
                type,
                "TOOL",
                input,
                output
        );
    }
}

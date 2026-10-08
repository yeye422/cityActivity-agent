package com.city.tool;

import com.city.model.context.AgentDecisionToolContext;
import com.city.model.tool.UserPreferenceToolResult;
import com.city.service.memory.PreferenceMemoryService;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Recommendation / Planning 共用的长期偏好只读 Tool。 */
@Component
public class UserPreferenceLookupTool {
    private final PreferenceMemoryService preferenceMemoryService;
    private final AgentTraceService traceService;

    public UserPreferenceLookupTool(PreferenceMemoryService preferenceMemoryService,
                                    AgentTraceService traceService) {
        this.preferenceMemoryService = Objects.requireNonNull(preferenceMemoryService, "preferenceMemoryService");
        this.traceService = traceService;
    }

    @Tool(
            name = "lookup_user_preferences",
            description = "Read stable long-term user preferences for soft ranking. Never changes hard constraints."
    )
    public UserPreferenceToolResult lookup(
            @ToolParam(name = "focus", description = "Optional soft-goal focus, e.g. novelty, date, relaxation")
            String focus,
            AgentDecisionToolContext context
    ) {
        Objects.requireNonNull(context, "context");
        String safeFocus = focus == null ? "" : focus.trim();
        UserPreferenceToolResult result = UserPreferenceToolResult.from(
                safeFocus,
                preferenceMemoryService.findActive(context.verifiedRequestContext().userId())
        );
        if (traceService != null) {
            traceService.recordEventForTrace(
                    context.verifiedRequestContext().traceId(),
                    "USER_PREFERENCE_TOOL_CALLED",
                    "TOOL",
                    Map.of("focus", safeFocus),
                    result
            );
        }
        return result;
    }
}

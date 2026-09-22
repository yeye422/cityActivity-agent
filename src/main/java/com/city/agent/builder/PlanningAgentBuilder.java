package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import com.city.model.context.PlanningToolContext;
import com.city.service.harness.AgentBusinessGuardHook;
import com.city.service.trace.AgentTraceService;
import com.city.tool.PlanValidationTool;
import com.city.tool.PlanningDiscoveryTool;
import com.city.tool.TravelTimeTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** 构建单次执行使用的 PlanningAgent。 */
@Component
public class PlanningAgentBuilder {

    private final Model mainModel;
    private final PromptLoader promptLoader;
    private final PlanningDiscoveryTool discoveryTool;
    private final TravelTimeTool travelTimeTool;
    private final PlanValidationTool validationTool;
    private final AgentTraceService traceService;

    /** 保留现有纯单测构造方式。 */
    public PlanningAgentBuilder(
            Model mainModel,
            PromptLoader promptLoader,
            PlanningDiscoveryTool discoveryTool,
            TravelTimeTool travelTimeTool,
            PlanValidationTool validationTool
    ) {
        this(mainModel, promptLoader, discoveryTool, travelTimeTool, validationTool, null);
    }

    @Autowired
    public PlanningAgentBuilder(
            @Qualifier("CityMainChatModel") Model mainModel,
            PromptLoader promptLoader,
            PlanningDiscoveryTool discoveryTool,
            TravelTimeTool travelTimeTool,
            PlanValidationTool validationTool,
            AgentTraceService traceService
    ) {
        this.mainModel = mainModel;
        this.promptLoader = promptLoader;
        this.discoveryTool = discoveryTool;
        this.travelTimeTool = travelTimeTool;
        this.validationTool = validationTool;
        this.traceService = traceService;
    }

    public ReActAgent build(PlanningToolContext planningContext) {
        Objects.requireNonNull(planningContext, "planningContext");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(discoveryTool);
        toolkit.registerTool(travelTimeTool);
        toolkit.registerTool(validationTool);

        ToolExecutionContext toolContext = ToolExecutionContext.builder()
                .register(planningContext)
                .build();

        AgentBusinessGuardHook guardHook = new AgentBusinessGuardHook(
                "city_planning_agent",
                Set.of("discover_plan_candidates", "get_travel_time", "validate_plan"),
                10,
                3,
                traceService
        );

        return ReActAgent.builder()
                .name("city_planning_agent")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/planning-decision.txt"))
                .toolkit(toolkit)
                .toolExecutionContext(toolContext)
                .memory(new InMemoryMemory())
                .hook(guardHook)
                .maxIters(8)
                .build();
    }
}

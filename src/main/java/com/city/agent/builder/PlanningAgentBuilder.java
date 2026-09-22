package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import com.city.model.context.PlanningToolContext;
import com.city.tool.PlanValidationTool;
import com.city.tool.PlanningDiscoveryTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 构建单次执行使用的 PlanningAgent。 */
@Component
public class PlanningAgentBuilder {

    private final Model mainModel;
    private final PromptLoader promptLoader;
    private final PlanningDiscoveryTool discoveryTool;
    private final PlanValidationTool validationTool;

    public PlanningAgentBuilder(
            @Qualifier("CityMainChatModel") Model mainModel,
            PromptLoader promptLoader,
            PlanningDiscoveryTool discoveryTool,
            PlanValidationTool validationTool
    ) {
        this.mainModel = mainModel;
        this.promptLoader = promptLoader;
        this.discoveryTool = discoveryTool;
        this.validationTool = validationTool;
    }

    public ReActAgent build(PlanningToolContext planningContext) {
        Objects.requireNonNull(planningContext, "planningContext");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(discoveryTool);
        toolkit.registerTool(validationTool);

        ToolExecutionContext toolContext = ToolExecutionContext.builder()
                .register(planningContext)
                .build();

        return ReActAgent.builder()
                .name("city_planning_agent")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/planning-decision.txt"))
                .toolkit(toolkit)
                .toolExecutionContext(toolContext)
                .memory(new InMemoryMemory())
                .maxIters(8)
                .build();
    }
}

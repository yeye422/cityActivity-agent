package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** PlanResponseAgent 构建器：多时段规划、时间推理与口语包装。 */
@Component
public class PlanResponseAgentBuilder {

    private final Model planModel;
    private final PromptLoader promptLoader;

    public PlanResponseAgentBuilder(
            @Qualifier("CityMainChatModel") Model planModel,
            PromptLoader promptLoader
    ) {
        this.planModel = planModel;
        this.promptLoader = promptLoader;
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("city_plan_response_agent")
                .model(planModel)
                .sysPrompt(promptLoader.load("city-prompts/plan-response.txt"))
                .memory(new InMemoryMemory())
                .build();
    }
}

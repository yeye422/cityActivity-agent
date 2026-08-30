package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** PlanResponseAgent 构建器：多时段规划理由与口语包装。 */
@Component
public class PlanResponseAgentBuilder {

    private final Model responseModel;
    private final PromptLoader promptLoader;

    public PlanResponseAgentBuilder(
            @Qualifier("DietResponseChatModel") Model responseModel,
            PromptLoader promptLoader
    ) {
        this.responseModel = responseModel;
        this.promptLoader = promptLoader;
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("diet_plan_response_agent")
                .model(responseModel)
                .sysPrompt(promptLoader.load("diet/prompts/plan-response.txt"))
                .memory(new InMemoryMemory())
                .build();
    }
}

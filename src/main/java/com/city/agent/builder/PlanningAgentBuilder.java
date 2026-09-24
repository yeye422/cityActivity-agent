package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 构建单次执行使用的 PlanningAgent；候选、路线和校验均由 Java 边界提供。 */
@Component
public class PlanningAgentBuilder {

    private final Model mainModel;
    private final PromptLoader promptLoader;

    public PlanningAgentBuilder(
            @Qualifier("CityMainChatModel") Model mainModel,
            PromptLoader promptLoader
    ) {
        this.mainModel = Objects.requireNonNull(mainModel, "mainModel");
        this.promptLoader = Objects.requireNonNull(promptLoader, "promptLoader");
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("city_planning_agent")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/planning-decision.txt"))
                .maxIters(4)
                .build();
    }
}

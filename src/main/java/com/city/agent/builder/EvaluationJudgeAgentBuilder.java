package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class EvaluationJudgeAgentBuilder {
    private final Model lightModel;
    private final PromptLoader promptLoader;

    public EvaluationJudgeAgentBuilder(@Qualifier("CityLightChatModel") Model lightModel, PromptLoader promptLoader) {
        this.lightModel = lightModel;
        this.promptLoader = promptLoader;
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("city_evaluation_judge_agent")
                .model(lightModel)
                .sysPrompt(promptLoader.load("city-prompts/evaluation-judge.txt"))
                .memory(new InMemoryMemory())
                .build();
    }
}

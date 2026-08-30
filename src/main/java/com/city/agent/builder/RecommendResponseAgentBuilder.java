package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** RecommendResponseAgent 构建器。 */
@Component
public class RecommendResponseAgentBuilder {
    /** 轻量响应模型用于生成推荐理由和最终口语回复。 */
    private final Model responseModel;
    private final PromptLoader promptLoader;

    public RecommendResponseAgentBuilder(
            @Qualifier("DietResponseChatModel") Model responseModel,
            PromptLoader promptLoader
    ) {
        this.responseModel = responseModel;
        this.promptLoader = promptLoader;
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("diet_recommend_response_agent")
                .model(responseModel)
                .sysPrompt(promptLoader.load("diet/prompts/recommend-response.txt"))
                .memory(new InMemoryMemory())
                .build();
    }
}

package com.city.agent.builder;

import com.city.agent.loader.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * IntentAgent 构建器。
 * 该 Agent 负责意图分类、槽位归一和时间 Patch 提取，优先使用主模型提升复杂语义解析准确率。
 */
@Component
public class IntentAgentBuilder {
    /** 主模型用于多轮语义理解、Patch 抽取和相对时间解析。 */
    private final Model mainModel;

    /** PromptLoader 用于加载现有 intent.txt。 */
    private final PromptLoader promptLoader;

    /** 构造器注入模型和 PromptLoader。 */
    public IntentAgentBuilder(@Qualifier("CityMainChatModel") Model mainModel, PromptLoader promptLoader) {
        this.mainModel = mainModel;
        this.promptLoader = promptLoader;
    }

    /** 构建一个新的 ReActAgent 实例，实例内部记忆只作为临时容器使用。 */
    public ReActAgent build() {
        return ReActAgent.builder()
                .name("city_intent_agent")
                .model(mainModel)
                .sysPrompt(promptLoader.load("city-prompts/intent.txt"))
                .memory(new InMemoryMemory())
                .build();
    }
}

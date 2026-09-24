package com.city.config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 模型配置。
 */
@Configuration
public class CityAgentScopeConfig {

    /** DashScope API Key，所有 AgentScope 模型调用都依赖该配置。 */
    @Value("${agentscope.dashscope.api-key:}")
    private String apiKey;

    /** 主模型保留给需要更强推理能力的任务。 */
    @Value("${city.llm.main-model:qwen3.8-max}")
    private String mainModelName;

    /** 轻量模型用于意图识别和澄清追问。 */
    @Value("${city.llm.light-model:qwen3.8-max}")
    private String lightModelName;

    /** 推荐理由与最终口语包装使用的轻量响应模型。 */
    @Value("${city.llm.response-model:qwen3.8-max}")
    private String responseModelName;

    @Bean("CityMainChatModel")
    public Model cityMainChatModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(mainModelName)
                .build();
    }

    @Bean("CityLightChatModel")
    public Model cityLightChatModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(lightModelName)
                .build();
    }

    /** RecommendResponseAgent / PlanResponseAgent 使用，降低推荐文案生成延迟。 */
    @Bean("CityResponseChatModel")
    public Model cityResponseChatModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(responseModelName)
                .build();
    }
}

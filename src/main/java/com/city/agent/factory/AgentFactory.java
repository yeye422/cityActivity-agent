package com.city.agent.factory;

import com.city.agent.builder.IntentAgentBuilder;
import com.city.agent.builder.PlanResponseAgentBuilder;
import com.city.agent.builder.RecommendResponseAgentBuilder;
import io.agentscope.core.ReActAgent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * newdiet 会话级 Agent 工厂。
 * 每个会话持有一套 Agent，避免不同用户的 Agent 内部记忆串话。
 */
@Component
public class AgentFactory {
    /** Agent 缓存最大容量，超过后按 LRU 淘汰最久未使用的会话。 */
    private static final int MAX_AGENT_SETS = 1000;

    private final IntentAgentBuilder intentBuilder;
    private final RecommendResponseAgentBuilder recommendResponseBuilder;
    private final PlanResponseAgentBuilder planResponseBuilder;
    /** Prompt 版本，Prompt 升级后通过缓存键避免复用旧 Agent。 */
    private final String promptVersion;

    private final Map<String, AgentSet> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AgentSet> eldest) {
                    return size() > MAX_AGENT_SETS;
                }
            }
    );

    public AgentFactory(
            IntentAgentBuilder intentBuilder,
            RecommendResponseAgentBuilder recommendResponseBuilder,
            PlanResponseAgentBuilder planResponseBuilder,
            @Value("${diet.prompt.version:v2}") String promptVersion
    ) {
        this.intentBuilder = intentBuilder;
        this.recommendResponseBuilder = recommendResponseBuilder;
        this.planResponseBuilder = planResponseBuilder;
        this.promptVersion = promptVersion;
    }

    public AgentSet get(String sessionId) {
        return cache.computeIfAbsent(cacheKey(sessionId), ignored -> new AgentSet(
                intentBuilder.build(),
                recommendResponseBuilder.build(),
                planResponseBuilder.build()
        ));
    }

    public void remove(String sessionId) {
        cache.remove(cacheKey(sessionId));
    }

    private String cacheKey(String sessionId) {
        return sessionId + "::" + promptVersion;
    }

    @Data
    @Accessors(fluent = true)
    @AllArgsConstructor
    public static class AgentSet {
        private ReActAgent intent;
        private ReActAgent recommendResponse;
        private ReActAgent planResponse;
    }
}

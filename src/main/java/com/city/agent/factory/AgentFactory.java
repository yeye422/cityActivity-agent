package com.city.agent.factory;

import com.city.agent.builder.IntentAgentBuilder;
import io.agentscope.core.ReActAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话级 IntentAgent 工厂。
 *
 * <p>RecommendationAgent / PlanningAgent 由各自 Builder 按运行创建，不再通过旧 ResponseAgent 缓存。
 * IntentAgent 继续按 sessionId + promptVersion 隔离缓存，避免跨会话记忆串扰。</p>
 */
@Component
public class AgentFactory {

    private static final int MAX_AGENTS = 1000;

    private final IntentAgentBuilder intentBuilder;
    private final String promptVersion;

    private final Map<String, ReActAgent> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ReActAgent> eldest) {
                    return size() > MAX_AGENTS;
                }
            }
    );

    public AgentFactory(
            IntentAgentBuilder intentBuilder,
            @Value("${city.prompt.version:v2}") String promptVersion
    ) {
        this.intentBuilder = intentBuilder;
        this.promptVersion = promptVersion;
    }

    public AgentSet get(String sessionId) {
        return new AgentSet(cache.computeIfAbsent(cacheKey(sessionId), ignored -> intentBuilder.build()));
    }

    public void remove(String sessionId) {
        cache.remove(cacheKey(sessionId));
    }

    private String cacheKey(String sessionId) {
        return sessionId + "::" + promptVersion;
    }

    /** 保留现有调用形态 get(sessionId).intent()，不保留旧响应 Agent。 */
    public record AgentSet(ReActAgent intent) {}
}

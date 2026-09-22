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
 * 会话级 Agent 工厂。
 *
 * <p>一条会话固定复用同一组 Intent / RecommendResponse / PlanResponse Agent，
 * 这样既保留 Agent 在单个会话内的上下文，又通过 sessionId 隔离不同用户会话，避免内部记忆串话。</p>
 *
 * <p>缓存键同时包含 promptVersion。Prompt 升级后旧 Agent 不会继续被新请求复用，
 * 从而避免“代码已经切到新 Prompt，但内存里仍运行旧 Agent”的版本混用问题。</p>
 */
@Component
public class AgentFactory {
    /** Agent 缓存最大容量；超过容量后按访问顺序淘汰最久未使用的会话。 */
    private static final int MAX_AGENT_SETS = 1000;

    private final IntentAgentBuilder intentBuilder;
    private final RecommendResponseAgentBuilder recommendResponseBuilder;
    private final PlanResponseAgentBuilder planResponseBuilder;

    /** Prompt 版本参与缓存键计算，用于在 Prompt 升级时自然切换到新的 Agent 实例。 */
    private final String promptVersion;

    /**
     * 基于 LinkedHashMap(accessOrder=true) 实现简单 LRU 缓存。
     * synchronizedMap 用于保护 Map 的单次访问；AgentSet 本身仍按会话维度由上层编排控制并发。
     */
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
            @Value("${city.prompt.version:v2}") String promptVersion
    ) {
        this.intentBuilder = intentBuilder;
        this.recommendResponseBuilder = recommendResponseBuilder;
        this.planResponseBuilder = planResponseBuilder;
        this.promptVersion = promptVersion;
    }

    /**
     * 获取当前会话对应的 Agent 集合；首次访问时才真正构建三个 Agent。
     *
     * @param sessionId 会话 ID
     * @return 该会话独占的 Agent 集合
     */
    public AgentSet get(String sessionId) {
        return cache.computeIfAbsent(cacheKey(sessionId), ignored -> new AgentSet(
                intentBuilder.build(),
                recommendResponseBuilder.build(),
                planResponseBuilder.build()
        ));
    }

    /**
     * 主动移除一个会话的 Agent 缓存，通常在会话结束或需要强制重建 Agent 时调用。
     */
    public void remove(String sessionId) {
        cache.remove(cacheKey(sessionId));
    }

    /** sessionId + Prompt 版本共同决定一个 AgentSet 的缓存身份。 */
    private String cacheKey(String sessionId) {
        return sessionId + "::" + promptVersion;
    }

    /**
     * 一个会话内使用的三类 Worker Agent。
     * Intent 负责结构化理解，RecommendResponse/PlanResponse 只负责对应场景的最终自然语言生成。
     */
    @Data
    @Accessors(fluent = true)
    @AllArgsConstructor
    public static class AgentSet {
        private ReActAgent intent;
        private ReActAgent recommendResponse;
        private ReActAgent planResponse;
    }
}

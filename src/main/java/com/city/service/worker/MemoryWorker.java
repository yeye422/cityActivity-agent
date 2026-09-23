package com.city.service.worker;

import com.city.model.MemoryMutationProposal;
import com.city.model.PreferenceFact;
import com.city.model.PreferenceFactRequest;
import com.city.service.memory.PreferenceMemoryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 长期偏好记忆 Worker。
 *
 * <p>只执行已经通过 Java Policy 的记忆读写，不负责自行判断“什么值得记住”。
 * 在线 Recommendation/Planning Agent 当前没有写 Memory Tool；未来 Agent 提议写入时必须走
 * rememberConfirmedAgentPreference，而不能调用显式用户写入口。</p>
 */
@Component
public final class MemoryWorker {
    private final PreferenceMemoryService preferenceMemoryService;

    public MemoryWorker(PreferenceMemoryService preferenceMemoryService) {
        this.preferenceMemoryService = Objects.requireNonNull(preferenceMemoryService, "preferenceMemoryService");
    }

    public List<PreferenceFact> findActive(Long userId) {
        return preferenceMemoryService.findActive(userId);
    }

    /** 用户在偏好 API 上显式保存。 */
    public PreferenceFact remember(Long userId, PreferenceFactRequest request) {
        return preferenceMemoryService.remember(userId, request);
    }

    /** 未来 Agent Proposal 经上层确认后使用的唯一写入口。 */
    public PreferenceFact rememberConfirmedAgentPreference(Long userId, MemoryMutationProposal proposal) {
        return preferenceMemoryService.rememberConfirmedAgentPreference(userId, proposal);
    }

    public void forget(Long userId, Long factId, Integer expectedVersion) {
        preferenceMemoryService.forget(userId, factId, expectedVersion);
    }
}

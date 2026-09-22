package com.city.service.worker;

import com.city.model.PreferenceFact;
import com.city.model.PreferenceFactRequest;
import com.city.service.memory.PreferenceMemoryService;

import java.util.List;
import java.util.Objects;

/**
 * 长期偏好记忆 Worker。
 *
 * <p>只执行已经通过上层 Policy Check 的显式记忆读写，不负责自行判断“什么值得记住”。</p>
 */
public final class MemoryWorker {
    private final PreferenceMemoryService preferenceMemoryService;

    public MemoryWorker(PreferenceMemoryService preferenceMemoryService) {
        this.preferenceMemoryService = Objects.requireNonNull(preferenceMemoryService, "preferenceMemoryService");
    }

    public List<PreferenceFact> findActive(Long userId) {
        return preferenceMemoryService.findActive(userId);
    }

    public PreferenceFact remember(Long userId, PreferenceFactRequest request) {
        return preferenceMemoryService.remember(userId, request);
    }

    public void forget(Long userId, Long factId, Integer expectedVersion) {
        preferenceMemoryService.forget(userId, factId, expectedVersion);
    }
}

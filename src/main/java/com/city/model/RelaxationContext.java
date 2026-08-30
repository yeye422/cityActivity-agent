package com.city.model;

import com.city.enums.SourceMode;

import java.util.List;

/**
 * 等待用户确认的放宽检索上下文。
 * <p>
 * RelaxationOption 只负责展示；真正执行用户选择时，必须复用这里保存的后端查询快照，
 * 避免前端重新传 sourceMode 或丢失“换一批”的历史排除 ID 后造成查询上下文漂移。
 */
public record RelaxationContext(
        SourceMode sourceMode,
        String queryKey,
        List<Long> excludeActivityIds,
        List<Integer> availableLevels
) {
    public RelaxationContext {
        queryKey = queryKey == null ? "" : queryKey;
        excludeActivityIds = excludeActivityIds == null ? List.of() : List.copyOf(excludeActivityIds);
        availableLevels = availableLevels == null ? List.of() : List.copyOf(availableLevels);
    }
}

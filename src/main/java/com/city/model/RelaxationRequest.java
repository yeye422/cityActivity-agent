package com.city.model;

import com.city.enums.SourceMode;

/**
 * 用户确认查看某个相近活动方案的请求。
 * <p>
 * sourceMode 暂时保留用于兼容已有前端请求，但后端执行放宽方案时不再信任该字段，
 * 实际数据源由 SessionState.pendingRelaxationContext 恢复。
 */
public record RelaxationRequest(String sessionId, SourceMode sourceMode, Integer level) {
}

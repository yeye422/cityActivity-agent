package com.city.model;

import com.city.enums.SourceMode;

/** 用户确认查看某个相近活动方案的请求。 */
public record RelaxationRequest(String sessionId, SourceMode sourceMode, Integer level) {
}

package com.city.model;

import lombok.Data;

import java.time.LocalDateTime;

/** SSE 可恢复事件的持久化行。eventJson 保存完整 AgentUiEvent，数据库字段只承担索引与游标定位。 */
@Data
public class AgentUiEventRow {
    private Long id;
    private Long userId;
    private String sessionId;
    private String traceId;
    private Integer eventSeq;
    private String eventJson;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}

package com.city.model.agent;

/** Supervisor 可派发的稳定任务类型，不把具体 Agent 类名暴露为业务协议。 */
public enum AgentTaskType {
    CONTEXT_UNDERSTANDING,
    ACTIVITY_DISCOVERY,
    MULTI_PERIOD_PLANNING,
    RESPONSE_GENERATION,
    MEMORY_MAINTENANCE
}

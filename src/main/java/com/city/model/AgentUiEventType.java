package com.city.model;

/** 前端只需处理的六类稳定执行事件。 */
public enum AgentUiEventType {
    RUN_STARTED,
    STEP_STARTED,
    STEP_COMPLETED,
    MESSAGE_COMPLETE,
    ERROR,
    RUN_FINISHED
}

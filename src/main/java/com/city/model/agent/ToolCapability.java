package com.city.model.agent;

/** Supervisor 可授予 Worker 的类型化最小能力。 */
public enum ToolCapability {
    CONTEXT_PARSE(ToolAccessMode.READ),
    ACTIVITY_SEARCH(ToolAccessMode.READ),
    ACTIVITY_SESSION_READ(ToolAccessMode.READ),
    VENUE_READ(ToolAccessMode.READ),
    WEATHER_READ(ToolAccessMode.READ),
    MAP_READ(ToolAccessMode.READ),
    RESPONSE_GENERATE(ToolAccessMode.READ),
    PREFERENCE_READ(ToolAccessMode.READ),
    PREFERENCE_WRITE(ToolAccessMode.WRITE),
    SESSION_STATE_WRITE(ToolAccessMode.WRITE);

    private final ToolAccessMode accessMode;

    ToolCapability(ToolAccessMode accessMode) {
        this.accessMode = accessMode;
    }

    public ToolAccessMode accessMode() {
        return accessMode;
    }
}

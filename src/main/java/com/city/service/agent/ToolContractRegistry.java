package com.city.service.agent;

import com.city.model.agent.AgentTask;
import com.city.model.agent.AgentTaskType;
import com.city.model.agent.ToolAccessMode;
import com.city.model.agent.ToolCapability;
import com.city.model.agent.ToolContract;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** 工具读写属性和 Worker 授权关系的唯一注册表。 */
@Service
public class ToolContractRegistry {
    private final Map<ToolCapability, ToolContract> contracts;

    public ToolContractRegistry() {
        EnumMap<ToolCapability, ToolContract> values = new EnumMap<>(ToolCapability.class);
        register(values, ToolCapability.CONTEXT_PARSE, "conversation-context",
                Set.of(AgentTaskType.CONTEXT_UNDERSTANDING));
        register(values, ToolCapability.ACTIVITY_SEARCH, "activity_item",
                Set.of(AgentTaskType.ACTIVITY_DISCOVERY, AgentTaskType.MULTI_PERIOD_PLANNING));
        register(values, ToolCapability.ACTIVITY_SESSION_READ, "activity_session",
                Set.of(AgentTaskType.ACTIVITY_DISCOVERY, AgentTaskType.MULTI_PERIOD_PLANNING));
        register(values, ToolCapability.VENUE_READ, "venue",
                Set.of(AgentTaskType.ACTIVITY_DISCOVERY, AgentTaskType.MULTI_PERIOD_PLANNING));
        register(values, ToolCapability.WEATHER_READ, "weather-provider",
                Set.of(AgentTaskType.ACTIVITY_DISCOVERY, AgentTaskType.MULTI_PERIOD_PLANNING));
        register(values, ToolCapability.MAP_READ, "map-provider",
                Set.of(AgentTaskType.ACTIVITY_DISCOVERY, AgentTaskType.MULTI_PERIOD_PLANNING));
        register(values, ToolCapability.RESPONSE_GENERATE, "response-model",
                Set.of(AgentTaskType.RESPONSE_GENERATION));
        register(values, ToolCapability.PREFERENCE_READ, "city_preference_fact",
                Set.of(AgentTaskType.ACTIVITY_DISCOVERY, AgentTaskType.MULTI_PERIOD_PLANNING));
        register(values, ToolCapability.PREFERENCE_WRITE, "city_preference_fact",
                Set.of(AgentTaskType.MEMORY_MAINTENANCE));
        register(values, ToolCapability.SESSION_STATE_WRITE, "city_sessions",
                Set.of()); // 只允许 Supervisor 直接持有，不授权给 Worker。
        contracts = Map.copyOf(values);
    }

    public ToolContract contract(ToolCapability capability) {
        ToolContract contract = contracts.get(capability);
        if (contract == null) throw new IllegalArgumentException("未注册的工具能力: " + capability);
        return contract;
    }

    public void validate(AgentTask task) {
        if (task == null) throw new IllegalArgumentException("AgentTask 不能为空");
        for (ToolCapability capability : task.allowedCapabilities()) {
            ToolContract contract = contract(capability);
            if (task.readOnly() && contract.accessMode() == ToolAccessMode.WRITE) {
                throw new IllegalArgumentException("只读 Worker 不能使用写能力: " + capability);
            }
            if (!contract.allowedTaskTypes().contains(task.type())) {
                throw new IllegalArgumentException("任务类型无权使用能力: " + task.type() + " -> " + capability);
            }
        }
    }

    private void register(Map<ToolCapability, ToolContract> target,
                          ToolCapability capability,
                          String resource,
                          Set<AgentTaskType> taskTypes) {
        target.put(capability, new ToolContract(
                capability, resource, capability.accessMode(), "v1", "v1", taskTypes));
    }
}

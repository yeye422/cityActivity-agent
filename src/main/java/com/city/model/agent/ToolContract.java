package com.city.model.agent;

import java.util.Set;

/** 工具能力的稳定注册信息。 */
public record ToolContract(
        ToolCapability capability,
        String resource,
        ToolAccessMode accessMode,
        String inputSchemaVersion,
        String outputSchemaVersion,
        Set<AgentTaskType> allowedTaskTypes
) {
    public ToolContract {
        if (capability == null || resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("工具合同缺少能力或资源名");
        }
        accessMode = accessMode == null ? capability.accessMode() : accessMode;
        if (accessMode != capability.accessMode()) {
            throw new IllegalArgumentException("工具合同访问属性与能力定义不一致");
        }
        inputSchemaVersion = inputSchemaVersion == null ? "v1" : inputSchemaVersion;
        outputSchemaVersion = outputSchemaVersion == null ? "v1" : outputSchemaVersion;
        allowedTaskTypes = allowedTaskTypes == null ? Set.of() : Set.copyOf(allowedTaskTypes);
    }
}

package com.city.model.agent;

/**
 * 单个 AgentTask 的执行预算覆盖项。
 * null 表示继承 Dispatcher / Harness 的全局默认值，避免在合同层提前写死运行参数。
 */
public record ExecutionBudget(
        Integer maxModelCalls,
        Integer maxToolCalls,
        Long maxTokens,
        Long maxDurationMillis
) {
    public ExecutionBudget {
        validatePositive("maxModelCalls", maxModelCalls);
        validatePositive("maxToolCalls", maxToolCalls);
        validatePositive("maxTokens", maxTokens);
        validatePositive("maxDurationMillis", maxDurationMillis);
    }

    public static ExecutionBudget inheritDefaults() {
        return new ExecutionBudget(null, null, null, null);
    }

    private static void validatePositive(String name, Number value) {
        if (value != null && value.longValue() <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}

package com.city.model.context;

/**
 * Workflow 在应用完本轮 Slot/Time Patch 后使用的统一语义视图。
 *
 * <p>HardConstraints 进入 RetrievalPipeline / PlanningSolver；UserGoal 进入 Agent 决策层。
 * SessionState 仍是唯一业务状态真相，该对象只是只读投影。</p>
 */
public record SemanticContext(
        HardConstraints hardConstraints,
        UserGoal userGoal
) {
    public SemanticContext {
        hardConstraints = hardConstraints == null ? HardConstraints.empty() : hardConstraints;
        userGoal = userGoal == null ? UserGoal.empty() : userGoal;
    }

    public static SemanticContext empty() {
        return new SemanticContext(HardConstraints.empty(), UserGoal.empty());
    }
}

package com.city.service.context;

import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.context.HardConstraints;
import com.city.model.context.SemanticContext;
import com.city.model.context.UserGoal;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把已经由 Orchestrator / Workflow 应用完本轮 Patch 的 SessionState 投影为决策语义上下文。
 *
 * <p>该服务不解析自然语言、不修改 SessionState，也不重新解释 IntentAgent 的输出。
 * 已标准化的九维标签通过 VerifiedRequestContext.effectiveSlots 进入 MySQL，
 * UserGoal 只读取独立存储的非字典开放目标。</p>
 *
 * <p>第一阶段故意保持保守：feature 仍沿用旧系统的确定性筛选语义，避免本次重构改变线上行为；
 * 后续如果 IntentAgent 引入显式约束强度，再把“最好近地铁”等偏好从硬条件迁移到 UserGoal。</p>
 */
@Service
public class SemanticContextBuilder {

    public SemanticContext build(SessionState state) {
        if (state == null) {
            return SemanticContext.empty();
        }

        SlotBundle slots = state.slots() == null ? SlotBundle.empty() : state.slots();
        SlotBundle excludedSlots = state.excludedSlots() == null ? SlotBundle.empty() : state.excludedSlots();
        TimeConstraint time = state.timeConstraint() == null ? TimeConstraint.empty() : state.timeConstraint();

        HardConstraints hardConstraints = new HardConstraints(
                slots.city(),
                slots.location(),
                slots.budget(),
                slots.activityType(),
                slots.duration(),
                slots.feature(),
                time,
                excludedSlots,
                historyExclusions(state.lastRecommendedActivityIds())
        );

        UserGoal userGoal = UserGoal.open(state.userGoals());

        return new SemanticContext(hardConstraints, userGoal);
    }

    private Set<Long> historyExclusions(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) normalized.add(id);
        }
        return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
    }
}

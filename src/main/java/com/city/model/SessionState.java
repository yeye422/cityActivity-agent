package com.city.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Orchestrator 独占写入的会话状态。
 * 该对象对应技术方案中的 SessionState，用于保存多轮对话中的槽位、阶段和上一轮推荐。
 */
@Data
@Accessors(fluent = true)
@AllArgsConstructor
public class SessionState {
    /** 当前会话 ID，前端多轮请求必须复用该值。 */
    private String sessionId;
    /** 当前用户 ID，用于 PERSONAL 数据源隔离。 */
    private Long userId;
    /** 当前会话阶段，决定下一轮上下文如何被解释。 */
    private SessionPhase phase;
    /** 当前会话实际使用的数据源模式。 */
    private SourceMode sourceMode;
    /** 当前或上一轮被 Orchestrator 确认的意图。 */
    private Intent currentIntent;
    /** 多轮累积后的标准活动属性槽位。 */
    private SlotBundle slots;
    /** 用户明确排除的标签，例如“不要展览”。 */
    private SlotBundle excludedSlots;
    /** 用户明确表示“不限”的字段名；用于区分“尚未回答”和“明确无约束”。 */
    private Set<String> unconstrainedSlots;
    /** 最近一次明确时间表达解析出的绝对日期/时段。 */
    private TimeConstraint timeConstraint;
    /** 生成上一批推荐时使用的完整检索约束摘要。 */
    private String recommendationQueryKey;
    /** 当前等待用户选择的放宽检索上下文；没有待选方案时为 null。 */
    private RelaxationContext pendingRelaxationContext;
    /** 本会话已推荐过的活动 ID（累积），用于“换一批”时排除重复。 */
    private List<Long> lastRecommendedActivityIds;

    /**
     * 创建一个新的空状态。
     * 该工厂方法用于数据库首次创建会话或旧数据缺少元信息时兜底。
     */
    public static SessionState fresh(String sessionId, Long userId, SourceMode sourceMode) {
        return new SessionState(
                sessionId,
                userId,
                SessionPhase.START,
                sourceMode,
                null,
                SlotBundle.empty(),
                SlotBundle.empty(),
                Set.of(),
                TimeConstraint.empty(),
                "",
                null,
                List.of()
        );
    }

    /** 返回更新阶段后的新状态。 */
    public SessionState withPhase(SessionPhase newPhase) {
        return copy(newPhase, sourceMode, currentIntent, slots, excludedSlots,
                safeUnconstrained(unconstrainedSlots), timeConstraint, recommendationQueryKey,
                pendingRelaxationContext, lastRecommendedActivityIds);
    }

    /** 返回更新意图后的新状态。 */
    public SessionState withIntent(Intent newIntent) {
        return copy(phase, sourceMode, newIntent, slots, excludedSlots,
                safeUnconstrained(unconstrainedSlots), timeConstraint, recommendationQueryKey,
                pendingRelaxationContext, lastRecommendedActivityIds);
    }

    /** 返回更新槽位后的新状态。 */
    public SessionState withSlots(SlotBundle newSlots) {
        return copy(phase, sourceMode, currentIntent, newSlots, excludedSlots,
                safeUnconstrained(unconstrainedSlots), timeConstraint, recommendationQueryKey,
                pendingRelaxationContext, lastRecommendedActivityIds);
    }

    public SessionState withExcludedSlots(SlotBundle value) {
        return copy(phase, sourceMode, currentIntent, slots,
                value == null ? SlotBundle.empty() : value, safeUnconstrained(unconstrainedSlots),
                timeConstraint, recommendationQueryKey, pendingRelaxationContext, lastRecommendedActivityIds);
    }

    public SessionState withUnconstrainedSlots(Set<String> value) {
        return copy(phase, sourceMode, currentIntent, slots, excludedSlots,
                safeUnconstrained(value), timeConstraint, recommendationQueryKey,
                pendingRelaxationContext, lastRecommendedActivityIds);
    }

    public SessionState withTimeConstraint(TimeConstraint value) {
        return copy(phase, sourceMode, currentIntent, slots, excludedSlots,
                safeUnconstrained(unconstrainedSlots), value == null ? timeConstraint : value,
                recommendationQueryKey, pendingRelaxationContext, lastRecommendedActivityIds);
    }

    public SessionState withRecommendationQueryKey(String value) {
        return copy(phase, sourceMode, currentIntent, slots, excludedSlots,
                safeUnconstrained(unconstrainedSlots), timeConstraint, value == null ? "" : value,
                pendingRelaxationContext, lastRecommendedActivityIds);
    }

    /** 保存或清除当前待选择的放宽查询上下文。 */
    public SessionState withPendingRelaxationContext(RelaxationContext value) {
        return copy(phase, sourceMode, currentIntent, slots, excludedSlots,
                safeUnconstrained(unconstrainedSlots), timeConstraint, recommendationQueryKey,
                value, lastRecommendedActivityIds);
    }

    /** 返回更新推荐历史后的新状态（覆盖）。 */
    public SessionState withLastRecommendations(List<Long> newLastRecommendations) {
        return copy(phase, sourceMode, currentIntent, slots, excludedSlots,
                safeUnconstrained(unconstrainedSlots), timeConstraint, recommendationQueryKey,
                pendingRelaxationContext,
                newLastRecommendations == null ? List.of() : List.copyOf(newLastRecommendations));
    }

    /** 将本轮推荐 ID 追加到累积历史，去重并保持插入顺序。 */
    public SessionState appendLastRecommendations(List<Long> newIds) {
        if (newIds == null || newIds.isEmpty()) {
            return this;
        }
        LinkedHashSet<Long> merged = new LinkedHashSet<>(
                lastRecommendedActivityIds == null ? List.of() : lastRecommendedActivityIds);
        merged.addAll(newIds);
        return copy(phase, sourceMode, currentIntent, slots, excludedSlots,
                safeUnconstrained(unconstrainedSlots), timeConstraint, recommendationQueryKey,
                pendingRelaxationContext, List.copyOf(merged));
    }

    /** 返回更新数据源模式后的新状态。 */
    public SessionState withSourceMode(SourceMode newSourceMode) {
        return copy(phase, newSourceMode, currentIntent, slots, excludedSlots,
                safeUnconstrained(unconstrainedSlots), timeConstraint, recommendationQueryKey,
                pendingRelaxationContext, lastRecommendedActivityIds);
    }

    private SessionState copy(SessionPhase newPhase,
                              SourceMode newSourceMode,
                              Intent newIntent,
                              SlotBundle newSlots,
                              SlotBundle newExcludedSlots,
                              Set<String> newUnconstrainedSlots,
                              TimeConstraint newTimeConstraint,
                              String newRecommendationQueryKey,
                              RelaxationContext newPendingRelaxationContext,
                              List<Long> newLastRecommendedActivityIds) {
        return new SessionState(
                sessionId,
                userId,
                newPhase,
                newSourceMode,
                newIntent,
                newSlots,
                newExcludedSlots,
                newUnconstrainedSlots,
                newTimeConstraint,
                newRecommendationQueryKey,
                newPendingRelaxationContext,
                newLastRecommendedActivityIds
        );
    }

    private static Set<String> safeUnconstrained(Set<String> value) {
        return value == null || value.isEmpty() ? Set.of() : Set.copyOf(value);
    }
}

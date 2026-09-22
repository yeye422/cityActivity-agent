package com.city.model.context;

import com.city.enums.SourceMode;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;

import java.util.Objects;

/**
 * AgentScope Tool 的模型不可见业务上下文。
 *
 * <p>LLM 只提供诸如 retrievalIntent 这样的软决策参数；用户身份、数据源、硬约束、
 * 历史排除和已验证会话状态由 AgentScope v1 ToolExecutionContext 按类型注入 Tool，
 * 避免模型自行改写预算、时间、数据源等确定性条件。</p>
 */
public record VerifiedRequestContext(
        Long userId,
        String sessionId,
        String traceId,
        SourceMode sourceMode,
        HardConstraints hardConstraints,
        UserGoal userGoal,
        SlotBundle effectiveSlots,
        WeatherRecommendationContext weather
) {
    public VerifiedRequestContext {
        userId = Objects.requireNonNull(userId, "userId");
        sessionId = requireText(sessionId, "sessionId");
        traceId = traceId == null ? "" : traceId.trim();
        sourceMode = Objects.requireNonNull(sourceMode, "sourceMode");
        hardConstraints = hardConstraints == null ? HardConstraints.empty() : hardConstraints;
        userGoal = userGoal == null ? UserGoal.empty() : userGoal;
        effectiveSlots = effectiveSlots == null ? SlotBundle.empty() : copySlots(effectiveSlots);
        weather = weather == null ? WeatherRecommendationContext.inactive() : weather;
    }

    public static VerifiedRequestContext from(SessionState state,
                                              String traceId,
                                              SemanticContext semanticContext,
                                              WeatherRecommendationContext weather) {
        Objects.requireNonNull(state, "state");
        SemanticContext safeSemantic = semanticContext == null ? SemanticContext.empty() : semanticContext;
        return new VerifiedRequestContext(
                state.userId(),
                state.sessionId(),
                traceId,
                state.sourceMode(),
                safeSemantic.hardConstraints(),
                safeSemantic.userGoal(),
                state.slots(),
                weather
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    private static SlotBundle copySlots(SlotBundle slots) {
        return new SlotBundle(
                slots.city(), slots.location(), slots.experienceGoal(), slots.companion(), slots.budget(),
                slots.activityType(), slots.style(), slots.duration(), slots.feature()
        );
    }
}

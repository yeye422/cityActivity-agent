package com.city.service.context;

import com.city.enums.SourceMode;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.context.HardConstraints;
import com.city.model.context.VerifiedRequestContext;
import com.city.model.context.SemanticContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticContextBuilderTest {

    private final SemanticContextBuilder builder = new SemanticContextBuilder();

    @Test
    void shouldSplitHardConstraintsAndSoftGoalsWithoutChangingSessionState() {
        SlotBundle slots = new SlotBundle(
                List.of("上海"),
                List.of("浦东"),
                List.of("放松", "新鲜"),
                List.of("情侣"),
                List.of("200元内"),
                List.of("手作"),
                List.of("安静"),
                List.of("1-2小时"),
                List.of("室内")
        );
        SlotBundle excluded = new SlotBundle(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("展览"), List.of(), List.of(), List.of()
        );
        TimeConstraint time = new TimeConstraint(
                "周六下午",
                LocalDate.of(2026, 9, 26),
                LocalDate.of(2026, 9, 26),
                LocalTime.of(14, 0),
                LocalTime.of(18, 0),
                LocalDateTime.of(2026, 9, 22, 19, 0)
        );

        SessionState state = SessionState.fresh("s1", 1L, SourceMode.PUBLIC)
                .withSlots(slots)
                .withExcludedSlots(excluded)
                .withTimeConstraint(time)
                .withUserGoals(List.of("希望剧情有反转", "想要新鲜感"))
                .withLastRecommendations(List.of(101L, 102L));

        SemanticContext context = builder.build(state);

        VerifiedRequestContext verified = VerifiedRequestContext.from(
                state, "trace-1", context, WeatherRecommendationContext.inactive());

        // 全部九维正向条件只存在于可信上下文的 effectiveSlots 中。
        assertEquals(slots, verified.effectiveSlots());
        assertEquals(List.of("上海"), verified.effectiveSlots().city());
        assertEquals(List.of("浦东"), verified.effectiveSlots().location());
        assertEquals(List.of("放松", "新鲜"), verified.effectiveSlots().experienceGoal());
        assertEquals(List.of("情侣"), verified.effectiveSlots().companion());
        assertEquals(List.of("200元内"), verified.effectiveSlots().budget());
        assertEquals(List.of("手作"), verified.effectiveSlots().activityType());
        assertEquals(List.of("安静"), verified.effectiveSlots().style());
        assertEquals(List.of("1-2小时"), verified.effectiveSlots().duration());
        assertEquals(List.of("室内"), verified.effectiveSlots().feature());

        // HardConstraints 仅保留时间、排除槽位与排除活动 ID。
        assertEquals(3, HardConstraints.class.getRecordComponents().length);
        assertEquals(time, context.hardConstraints().timeConstraint());
        assertEquals(List.of("展览"), context.hardConstraints().excludedSlots().activityType());
        assertTrue(context.hardConstraints().excludedActivityIds().containsAll(List.of(101L, 102L)));

        assertEquals(List.of("希望剧情有反转", "想要新鲜感"), context.userGoal().goals());
        assertTrue(context.userGoal().experienceGoals().isEmpty());
        assertTrue(context.userGoal().companions().isEmpty());
        assertTrue(context.userGoal().styles().isEmpty());

        // Builder 只能读取状态，不能反向修改九维槽位。
        assertEquals(List.of("放松", "新鲜"), state.slots().experienceGoal());
        assertEquals(List.of("200元内"), state.slots().budget());
    }

    @Test
    void shouldReturnEmptyContextForNullState() {
        SemanticContext context = builder.build(null);

        assertTrue(context.userGoal().isEmpty());
        assertEquals(TimeConstraint.empty(), context.hardConstraints().timeConstraint());
        assertEquals(SlotBundle.empty(), context.hardConstraints().excludedSlots());
        assertTrue(context.hardConstraints().excludedActivityIds().isEmpty());
    }
}

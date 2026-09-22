package com.city.service.context;

import com.city.enums.SourceMode;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
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
                .withLastRecommendations(List.of(101L, 102L));

        SemanticContext context = builder.build(state);

        assertEquals(List.of("上海"), context.hardConstraints().cities());
        assertEquals(List.of("浦东"), context.hardConstraints().locations());
        assertEquals(List.of("200元内"), context.hardConstraints().budgets());
        assertEquals(List.of("手作"), context.hardConstraints().activityTypes());
        assertEquals(List.of("1-2小时"), context.hardConstraints().durations());
        assertEquals(List.of("室内"), context.hardConstraints().features());
        assertEquals(time, context.hardConstraints().timeConstraint());
        assertEquals(List.of("展览"), context.hardConstraints().excludedSlots().activityType());
        assertTrue(context.hardConstraints().excludedActivityIds().containsAll(List.of(101L, 102L)));

        assertEquals(List.of("放松", "新鲜"), context.userGoal().experienceGoals());
        assertEquals(List.of("情侣"), context.userGoal().companions());
        assertEquals(List.of("安静"), context.userGoal().styles());

        // Builder 只能读取状态，不能反向修改九维槽位。
        assertEquals(List.of("放松", "新鲜"), state.slots().experienceGoal());
        assertEquals(List.of("200元内"), state.slots().budget());
    }

    @Test
    void shouldReturnEmptyContextForNullState() {
        SemanticContext context = builder.build(null);

        assertTrue(context.userGoal().isEmpty());
        assertTrue(context.hardConstraints().cities().isEmpty());
        assertTrue(context.hardConstraints().excludedActivityIds().isEmpty());
    }
}

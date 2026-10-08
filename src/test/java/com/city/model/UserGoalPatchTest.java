package com.city.model;

import com.city.enums.ConstraintOperationType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserGoalPatchTest {
    @Test
    void shouldApplyAddSetRemoveClearAcrossTurns() {
        List<String> current = List.of("有剧情反转");
        current = new UserGoalPatch(ConstraintOperationType.ADD, List.of("共同解决问题"))
                .apply(current);
        assertEquals(List.of("有剧情反转", "共同解决问题"), current);
        current = new UserGoalPatch(ConstraintOperationType.REMOVE, List.of("有剧情反转"))
                .apply(current);
        assertEquals(List.of("共同解决问题"), current);
        current = new UserGoalPatch(ConstraintOperationType.SET, List.of("轻松不费脑"))
                .apply(current);
        assertEquals(List.of("轻松不费脑"), current);
        current = new UserGoalPatch(ConstraintOperationType.CLEAR, List.of()).apply(current);
        assertEquals(List.of(), current);
    }
}

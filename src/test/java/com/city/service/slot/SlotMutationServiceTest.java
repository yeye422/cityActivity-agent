package com.city.service.slot;

import com.city.enums.ConstraintOperationType;
import com.city.model.ConstraintOperation;
import com.city.model.SlotBundle;
import com.city.model.SlotMutation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotMutationServiceTest {

    private final SlotMutationService service = new SlotMutationService();

    @Test
    void addShouldAppendIncludedValueAndRemoveItFromExcluded() {
        SlotBundle included = slots(List.of("上海"), List.of("室内"));
        SlotBundle excluded = slots(List.of(), List.of("近地铁"));
        ConstraintOperation add = new ConstraintOperation(
                "feature", ConstraintOperationType.ADD, List.of("近地铁"), "最好近地铁");

        SlotMutation mutation = service.apply(List.of(add), included, excluded, Set.of());

        assertEquals(List.of("室内", "近地铁"), mutation.included().feature());
        assertTrue(mutation.excluded().feature().isEmpty());
    }

    @Test
    void setShouldReplaceIncludedValues() {
        SlotBundle included = slots(List.of("上海"), List.of("室内", "近地铁"));
        ConstraintOperation set = new ConstraintOperation(
                "feature", ConstraintOperationType.SET, List.of("户外"), "改成户外");

        SlotMutation mutation = service.apply(List.of(set), included, SlotBundle.empty(), Set.of());

        assertEquals(List.of("户外"), mutation.included().feature());
    }

    @Test
    void removeShouldMoveValueFromIncludedToExcluded() {
        SlotBundle included = slots(List.of("上海"), List.of("室内", "户外"));
        ConstraintOperation remove = new ConstraintOperation(
                "feature", ConstraintOperationType.REMOVE, List.of("户外"), "不要户外");

        SlotMutation mutation = service.apply(List.of(remove), included, SlotBundle.empty(), Set.of());

        assertEquals(List.of("室内"), mutation.included().feature());
        assertEquals(List.of("户外"), mutation.excluded().feature());
    }

    @Test
    void clearShouldOverrideOtherOperationsForSameField() {
        SlotBundle included = slots(List.of("上海"), List.of("室内"));
        ConstraintOperation add = new ConstraintOperation(
                "feature", ConstraintOperationType.ADD, List.of("近地铁"), "近地铁");
        ConstraintOperation clear = new ConstraintOperation(
                "feature", ConstraintOperationType.CLEAR, List.of(), "特征不限");

        SlotMutation mutation = service.apply(List.of(add, clear), included, SlotBundle.empty(), Set.of());

        assertTrue(mutation.included().feature().isEmpty());
        assertTrue(mutation.excluded().feature().isEmpty());
        assertTrue(mutation.unconstrained().contains("feature"));
    }

    @Test
    void addShouldLeaveUnrelatedFieldsUntouched() {
        SlotBundle included = slots(List.of("上海"), List.of());
        ConstraintOperation add = new ConstraintOperation(
                "feature", ConstraintOperationType.ADD, List.of("室内"), "室内");

        SlotMutation mutation = service.apply(List.of(add), included, SlotBundle.empty(), Set.of("budget"));

        assertEquals(List.of("上海"), mutation.included().city());
        assertEquals(List.of("室内"), mutation.included().feature());
        assertTrue(mutation.unconstrained().contains("budget"));
        assertFalse(mutation.unconstrained().contains("feature"));
    }

    private SlotBundle slots(List<String> city, List<String> feature) {
        return new SlotBundle(
                city,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                feature
        );
    }
}

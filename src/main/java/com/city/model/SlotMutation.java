package com.city.model;

import java.util.Set;

/** 一轮自然语言对已保存筛选条件的增删改补丁。 */
public record SlotMutation(SlotBundle included, SlotBundle excluded, Set<String> unconstrained) {
    public SlotMutation {
        included = included == null ? SlotBundle.empty() : included;
        excluded = excluded == null ? SlotBundle.empty() : excluded;
        unconstrained = unconstrained == null || unconstrained.isEmpty() ? Set.of() : Set.copyOf(unconstrained);
    }

    public static SlotMutation empty() {
        return new SlotMutation(SlotBundle.empty(), SlotBundle.empty(), Set.of());
    }
}

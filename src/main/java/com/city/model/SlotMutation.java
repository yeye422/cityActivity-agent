package com.city.model;

/** 一轮自然语言对已保存筛选条件的增删改补丁。 */
public record SlotMutation(SlotBundle included, SlotBundle excluded) {
    public static SlotMutation empty() {
        return new SlotMutation(SlotBundle.empty(), SlotBundle.empty());
    }
}

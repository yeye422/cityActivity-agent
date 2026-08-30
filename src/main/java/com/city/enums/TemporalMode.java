package com.city.enums;

/**
 * 时间约束的多轮修改语义。
 * KEEP 表示本轮未修改该维度，SET 表示替换，CLEAR 表示取消限制。
 */
public enum TemporalMode {
    KEEP,
    SET,
    CLEAR
}

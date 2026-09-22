package com.city.model;

import com.city.enums.TemporalMode;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * IntentAgent 对“本轮时间条件变化”的结构化输出。
 * LLM 直接基于 prompt 中注入的当前日期/时间解析出绝对日期时间；
 * Java 只负责校验并按 KEEP / SET / CLEAR 合并历史 TimeConstraint。
 */
public record TemporalMutation(
        String raw,
        TemporalMode dateMode,
        LocalDate dateStart,
        LocalDate dateEnd,
        TemporalMode timeMode,
        LocalTime timeStart,
        LocalTime timeEnd,
        boolean approximate,
        double confidence
) {
    public static TemporalMutation keep() {
        return new TemporalMutation(
                "",
                TemporalMode.KEEP,
                null,
                null,
                TemporalMode.KEEP,
                null,
                null,
                false,
                0.0
        );
    }

    public boolean changesAnything() {
        return dateMode != TemporalMode.KEEP || timeMode != TemporalMode.KEEP;
    }
}

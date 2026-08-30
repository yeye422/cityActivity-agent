package com.city.model;

import com.city.enums.Intent;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * IntentAgent 的结构化输出。
 * 正常情况下以模型语义判断为准；fallback=true 仅表示模型调用或结构解析失败后使用了 Java 兜底。
 */
@Data
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@AllArgsConstructor
public class IntentResult {
    /** 当前用户输入的意图。 */
    private Intent intent;
    /** 当前输入抽取出的标准 8 槽位。 */
    private SlotBundle slots;
    /** LLM 对分类结果的置信度；fallback 结果通常较低。 */
    private double confidence;
    /** 当前句对普通会话约束的增删改补丁；时间改动由 temporal 单独表达。 */
    private List<ConstraintOperation> operations;
    /** 当前句对时间条件的 KEEP / SET / CLEAR 结构化补丁。 */
    private TemporalMutation temporal;
    /** 是否由模型失败后的 Java fallback 生成。 */
    private boolean fallback;

    public IntentResult(Intent intent, SlotBundle slots, double confidence) {
        this(intent, slots, confidence, List.of(), TemporalMutation.keep(), false);
    }

    public IntentResult(Intent intent, SlotBundle slots, double confidence, List<ConstraintOperation> operations) {
        this(intent, slots, confidence, operations, TemporalMutation.keep(), false);
    }

    public IntentResult(Intent intent,
                        SlotBundle slots,
                        double confidence,
                        List<ConstraintOperation> operations,
                        TemporalMutation temporal) {
        this(intent, slots, confidence, operations, temporal, false);
    }

    /** 构造模型调用/解析失败后的 Java fallback 结果。 */
    public static IntentResult fallback(Intent intent, SlotBundle slots, double confidence) {
        return new IntentResult(
                intent,
                slots == null ? SlotBundle.empty() : slots,
                confidence,
                List.of(),
                TemporalMutation.keep(),
                true
        );
    }

    /** 构造一个保守的澄清结果；它代表系统兜底而不是成功的模型判断。 */
    public static IntentResult clarify(SlotBundle slots) {
        return fallback(Intent.CLARIFY_NEEDED, slots, 0.2);
    }
}

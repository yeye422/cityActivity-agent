package com.city.model;

import com.city.enums.Intent;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * IntentAgent 的结构化输出。
 *
 * <p>普通九维条件只通过 operations 表达增删改清；时间条件只通过 temporal 表达。
 * IntentResult 不携带本轮 slots 快照，避免同一语义同时存在“槽位值 + 操作”两套状态变更协议。</p>
 *
 * <p>正常情况下以模型语义判断为准；fallback=true 仅表示模型调用或结构解析失败后使用了 Java 兜底。</p>
 */
@Data
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@AllArgsConstructor
public class IntentResult {
    /** 当前用户输入的业务意图。 */
    private Intent intent;
    /** LLM 对分类结果的置信度；fallback 结果通常较低。 */
    private double confidence;
    /** 当前句对九维普通约束的唯一结构化 Patch。 */
    private List<ConstraintOperation> operations;
    /** 当前句对时间条件的 KEEP / SET / CLEAR 结构化 Patch。 */
    private TemporalMutation temporal;
    /** 是否由模型失败后的 Java fallback 生成。 */
    private boolean fallback;

    public IntentResult(Intent intent, double confidence) {
        this(intent, confidence, List.of(), TemporalMutation.keep(), false);
    }

    public IntentResult(Intent intent, double confidence, List<ConstraintOperation> operations) {
        this(intent, confidence, operations, TemporalMutation.keep(), false);
    }

    public IntentResult(Intent intent,
                        double confidence,
                        List<ConstraintOperation> operations,
                        TemporalMutation temporal) {
        this(intent, confidence, operations, temporal, false);
    }

    /**
     * 构造模型异常时的保守业务结果。
     * 澄清不再是 Intent；后续 Orchestrator 会按推荐前置条件决定是否需要追问。
     */
    public static IntentResult fallbackRecommendation() {
        return new IntentResult(
                Intent.MEAL_RECOMMENDATION,
                0.2,
                List.of(),
                TemporalMutation.keep(),
                true
        );
    }
}

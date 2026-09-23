package com.city.model;

import com.city.enums.Intent;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * IntentAgent 的结构化输出。
 *
 * <p>普通九维条件只通过 operations 表达增删改清；时间条件只通过 temporal 表达。
 * memoryProposals 只表达明确长期偏好/排除的候选写入，最终仍由 Java MemoryPolicy 决定是否持久化。</p>
 */
@Data
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class IntentResult {
    private Intent intent;
    private double confidence;
    private List<ConstraintOperation> operations;
    private TemporalMutation temporal;
    private List<MemoryMutationProposal> memoryProposals;
    private boolean fallback;

    public IntentResult(Intent intent,
                        double confidence,
                        List<ConstraintOperation> operations,
                        TemporalMutation temporal,
                        List<MemoryMutationProposal> memoryProposals,
                        boolean fallback) {
        this.intent = intent;
        this.confidence = confidence;
        this.operations = operations == null ? List.of() : List.copyOf(operations);
        this.temporal = temporal == null ? TemporalMutation.keep() : temporal;
        this.memoryProposals = memoryProposals == null ? List.of() : List.copyOf(memoryProposals);
        this.fallback = fallback;
    }

    public IntentResult(Intent intent, double confidence) {
        this(intent, confidence, List.of(), TemporalMutation.keep(), List.of(), false);
    }

    public IntentResult(Intent intent, double confidence, List<ConstraintOperation> operations) {
        this(intent, confidence, operations, TemporalMutation.keep(), List.of(), false);
    }

    public IntentResult(Intent intent,
                        double confidence,
                        List<ConstraintOperation> operations,
                        TemporalMutation temporal) {
        this(intent, confidence, operations, temporal, List.of(), false);
    }

    public IntentResult(Intent intent,
                        double confidence,
                        List<ConstraintOperation> operations,
                        TemporalMutation temporal,
                        boolean fallback) {
        this(intent, confidence, operations, temporal, List.of(), fallback);
    }

    public static IntentResult fallbackRecommendation() {
        return new IntentResult(
                Intent.ACTIVITY_RECOMMENDATION,
                0.2,
                List.of(),
                TemporalMutation.keep(),
                List.of(),
                true
        );
    }
}

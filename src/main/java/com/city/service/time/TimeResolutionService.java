package com.city.service.time;

import com.city.enums.TemporalMode;
import com.city.model.TemporalMutation;
import com.city.model.TimeConstraint;
import com.city.model.TimeResolutionResult;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 时间解析总入口：优先接受 LLM 的结构化 temporal；失败时使用 Java 规则兜底；
 * 若用户明显表达了时间但两种解析都失败，则返回 CLARIFY，禁止静默忽略时间条件。
 */
@Service
public class TimeResolutionService {

    private static final double MIN_LLM_TEMPORAL_CONFIDENCE = 0.60;

    private final TemporalValidator temporalValidator;
    private final TimeMutationService timeMutationService;
    private final TimeExpressionParser timeExpressionParser;

    public TimeResolutionService(
            TemporalValidator temporalValidator,
            TimeMutationService timeMutationService,
            TimeExpressionParser timeExpressionParser
    ) {
        this.temporalValidator = temporalValidator;
        this.timeMutationService = timeMutationService;
        this.timeExpressionParser = timeExpressionParser;
    }

    public TimeResolutionResult resolve(TimeConstraint historical, TemporalMutation temporal, String userInput) {
        TimeConstraint base = historical == null ? TimeConstraint.empty() : historical;
        TemporalMutation safeTemporal = temporal == null ? TemporalMutation.keep() : temporal;

        // LLM 明确理解为“时间保持不变”时直接保留历史状态，不再错误进入 fallback/澄清。
        if (isReliableKeepReference(safeTemporal)) {
            return new TimeResolutionResult(TimeResolutionResult.Status.UNCHANGED, base, safeTemporal.raw());
        }

        if (isReliableLlmTemporal(safeTemporal)) {
            TimeConstraint merged = timeMutationService.apply(base, safeTemporal);
            TimeResolutionResult.Status status = merged.hasConstraint()
                    ? TimeResolutionResult.Status.LLM_SUCCESS
                    : TimeResolutionResult.Status.CLEAR;
            return new TimeResolutionResult(status, merged, safeTemporal.raw());
        }

        String fallbackText = safeTemporal.raw() == null || safeTemporal.raw().isBlank()
                ? userInput
                : safeTemporal.raw();

        TemporalMutation clearMutation = clearMutation(fallbackText);
        if (clearMutation != null) {
            TimeConstraint merged = timeMutationService.apply(base, clearMutation);
            return new TimeResolutionResult(
                    merged.hasConstraint() ? TimeResolutionResult.Status.JAVA_FALLBACK : TimeResolutionResult.Status.CLEAR,
                    merged,
                    fallbackText
            );
        }

        TimeConstraint parsed = safeJavaParse(fallbackText);
        if (parsed.hasConstraint()) {
            TemporalMutation fallbackMutation = new TemporalMutation(
                    fallbackText == null ? "" : fallbackText,
                    parsed.hasDate() ? TemporalMode.SET : TemporalMode.KEEP,
                    parsed.hasDate() ? parsed.dateStart() : null,
                    parsed.hasDate() ? parsed.dateEnd() : null,
                    parsed.hasTime() ? TemporalMode.SET : TemporalMode.KEEP,
                    parsed.hasTime() ? parsed.startTime() : null,
                    parsed.hasTime() ? parsed.endTime() : null,
                    false,
                    1.0
            );
            TimeConstraint merged = timeMutationService.apply(base, fallbackMutation);
            return new TimeResolutionResult(TimeResolutionResult.Status.JAVA_FALLBACK, merged, fallbackText);
        }

        boolean llmTriedAndFailed = safeTemporal.changesAnything()
                || (safeTemporal.raw() != null && !safeTemporal.raw().isBlank());
        if (llmTriedAndFailed || timeExpressionParser.mentionsTime(userInput)) {
            return new TimeResolutionResult(TimeResolutionResult.Status.CLARIFY, base, fallbackText);
        }

        return new TimeResolutionResult(TimeResolutionResult.Status.UNCHANGED, base, "");
    }

    private boolean isReliableLlmTemporal(TemporalMutation temporal) {
        return temporal.changesAnything()
                && temporal.confidence() >= MIN_LLM_TEMPORAL_CONFIDENCE
                && temporalValidator.isValid(temporal);
    }

    private boolean isReliableKeepReference(TemporalMutation temporal) {
        if (temporal.dateMode() != TemporalMode.KEEP
                || temporal.timeMode() != TemporalMode.KEEP
                || temporal.confidence() < MIN_LLM_TEMPORAL_CONFIDENCE
                || !temporalValidator.isValid(temporal)) {
            return false;
        }
        String raw = temporal.raw() == null ? "" : temporal.raw().replaceAll("\\s+", "");
        return containsAny(raw,
                "还是之前那个时间", "还是之前的时间", "按之前的时间", "照之前的时间",
                "时间不变", "时间照旧", "时间保持不变", "还是原来的时间", "时间还是原来");
    }

    private TimeConstraint safeJavaParse(String text) {
        try {
            return timeExpressionParser.parse(text);
        } catch (RuntimeException ignored) {
            return TimeConstraint.empty();
        }
    }

    /** Java 兜底只处理几种明确的清除语义，避免和普通时间解析混在一起。 */
    private TemporalMutation clearMutation(String input) {
        String text = input == null ? "" : input.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (text.isBlank()) return null;

        if (containsAny(text, "时间不限", "不限时间", "不限制时间", "随时都行", "什么时候都行", "任何时候都可以", "时间无所谓", "不挑时间")
                || text.equals("随时")) {
            return new TemporalMutation(text, TemporalMode.CLEAR, null, null,
                    TemporalMode.CLEAR, null, null, false, 1.0);
        }
        if (containsAny(text, "几点都行", "几点都可以", "时间段不限", "时段不限")) {
            return new TemporalMutation(text, TemporalMode.KEEP, null, null,
                    TemporalMode.CLEAR, null, null, false, 1.0);
        }
        if (containsAny(text, "哪天都行", "哪天都可以", "日期不限", "日子不限")) {
            return new TemporalMutation(text, TemporalMode.CLEAR, null, null,
                    TemporalMode.KEEP, null, null, false, 1.0);
        }
        return null;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}

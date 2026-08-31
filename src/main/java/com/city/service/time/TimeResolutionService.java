package com.city.service.time;

import com.city.enums.TemporalMode;
import com.city.model.TemporalMutation;
import com.city.model.TimeConstraint;
import com.city.model.TimeResolutionResult;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 时间约束解析的统一决策入口。
 *
 * <p>处理顺序固定为：可靠的 LLM temporal → 明确的 Java CLEAR 语义 → Java 时间表达式解析 → CLARIFY。
 * 这样既优先利用模型的结构化理解，又保证模型解析失败时不会把用户明确说出的时间条件静默丢掉。</p>
 *
 * <p>最终输出 TimeResolutionResult，Orchestrator 只需要根据状态决定“更新会话时间 / 保持不变 / 追问”。</p>
 */
@Service
public class TimeResolutionService {

    /** LLM temporal 低于该置信度时不直接写入持久化时间状态，而是进入 Java fallback。 */
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

    /**
     * 将“历史时间状态 + 本轮模型 temporal + 用户原文”归并成一次确定的时间决策。
     *
     * @param historical 会话中已经持久化的时间约束
     * @param temporal IntentAgent 输出的结构化时间变更
     * @param userInput 用户本轮原文，供 Java fallback 判断
     * @return 时间解析状态以及合并后的 TimeConstraint
     */
    public TimeResolutionResult resolve(TimeConstraint historical, TemporalMutation temporal, String userInput) {
        TimeConstraint base = historical == null ? TimeConstraint.empty() : historical;
        TemporalMutation safeTemporal = temporal == null ? TemporalMutation.keep() : temporal;

        // 高置信 KEEP/KEEP 是 LLM 的有效解析结果：可能表示本轮没提时间，也可能明确要求沿用历史时间。
        // 这两种情况都不应触发 Java fallback，否则 fallback 可能误把普通文本识别成新时间条件。
        if (isReliableNoChange(safeTemporal)) {
            return new TimeResolutionResult(TimeResolutionResult.Status.UNCHANGED, base, safeTemporal.raw());
        }

        // 结构合法且达到置信度门槛时，LLM temporal 是第一优先级事实。
        if (isReliableLlmTemporal(safeTemporal)) {
            TimeConstraint merged = timeMutationService.apply(base, safeTemporal);
            TimeResolutionResult.Status status = merged.hasConstraint()
                    ? TimeResolutionResult.Status.LLM_SUCCESS
                    : TimeResolutionResult.Status.CLEAR;
            return new TimeResolutionResult(status, merged, safeTemporal.raw());
        }

        // 模型若抽出了 raw 时间片段，优先对该片段兜底；否则直接使用完整用户原文。
        String fallbackText = safeTemporal.raw() == null || safeTemporal.raw().isBlank()
                ? userInput
                : safeTemporal.raw();

        // “时间不限/哪天都行/几点都行”属于状态操作，先于普通日期时间解析处理。
        TemporalMutation clearMutation = clearMutation(fallbackText);
        if (clearMutation != null) {
            TimeConstraint merged = timeMutationService.apply(base, clearMutation);
            return new TimeResolutionResult(
                    merged.hasConstraint() ? TimeResolutionResult.Status.JAVA_FALLBACK : TimeResolutionResult.Status.CLEAR,
                    merged,
                    fallbackText
            );
        }

        // 普通 Java fallback 只负责把可识别的自然语言时间转换成结构化 TimeConstraint。
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

        // 用户明显提到了时间，但 LLM 和 Java 都无法可靠解析时必须追问，不能静默沿用/忽略。
        boolean llmTriedAndFailed = safeTemporal.changesAnything()
                || (safeTemporal.raw() != null && !safeTemporal.raw().isBlank());
        if (llmTriedAndFailed || timeExpressionParser.mentionsTime(userInput)) {
            return new TimeResolutionResult(TimeResolutionResult.Status.CLARIFY, base, fallbackText);
        }

        // 本轮完全没有时间语义时保持历史约束不变。
        return new TimeResolutionResult(TimeResolutionResult.Status.UNCHANGED, base, "");
    }

    /** 模型确实要求修改时间、置信度达标且结构通过校验，才允许直接更新状态。 */
    private boolean isReliableLlmTemporal(TemporalMutation temporal) {
        return temporal.changesAnything()
                && temporal.confidence() >= MIN_LLM_TEMPORAL_CONFIDENCE
                && temporalValidator.isValid(temporal);
    }

    /**
     * 判断 KEEP/KEEP 是否可信。
     * raw 为空表示本轮未提时间；raw 非空时只接受少量明确“沿用原时间”的表达。
     */
    private boolean isReliableNoChange(TemporalMutation temporal) {
        if (temporal.dateMode() != TemporalMode.KEEP
                || temporal.timeMode() != TemporalMode.KEEP
                || temporal.confidence() < MIN_LLM_TEMPORAL_CONFIDENCE
                || !temporalValidator.isValid(temporal)) {
            return false;
        }
        String raw = temporal.raw() == null ? "" : temporal.raw().replaceAll("\\s+", "");
        if (raw.isBlank()) return true;
        return containsAny(raw,
                "还是之前那个时间", "还是之前的时间", "按之前的时间", "照之前的时间",
                "时间不变", "时间照旧", "时间保持不变", "还是原来的时间", "时间还是原来");
    }

    /** Java fallback 自身异常不能打断主链路；解析失败统一返回空约束，由上层决定是否澄清。 */
    private TimeConstraint safeJavaParse(String text) {
        try {
            return timeExpressionParser.parse(text);
        } catch (RuntimeException ignored) {
            return TimeConstraint.empty();
        }
    }

    /**
     * Java 兜底只处理几种明确的清除语义，避免和普通时间解析混在一起。
     * CLEAR 日期和 CLEAR 时段可以分别发生，因此转换成 TemporalMutation 后仍统一交给 TimeMutationService 合并。
     */
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

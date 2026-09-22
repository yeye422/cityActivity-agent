package com.city.model;

import com.city.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 城市活动 RiskGuard 的结构化判断结果。
 * Orchestrator 根据 passed/riskLevel 决定是否放行当前请求或最终回答。
 */
@Data
@Accessors(fluent = true)
@AllArgsConstructor
public class RiskGuardResult {
    /** true 表示允许继续输出，false 表示需要拦截。 */
    private boolean passed;
    /** 风险等级，高风险必须保守处理。 */
    private RiskLevel riskLevel;
    /** 被命中的风险原因，便于 Trace 和调试。 */
    private List<String> blockedReasons;
    /** 拦截时使用的保守改写建议。 */
    private String rewriteSuggestion;

    /** 低风险放行结果。 */
    public static RiskGuardResult pass() {
        return new RiskGuardResult(true, RiskLevel.LOW, List.of(), null);
    }

    /** 高风险拦截结果。 */
    public static RiskGuardResult block(List<String> reasons, String suggestion) {
        return new RiskGuardResult(false, RiskLevel.HIGH, reasons == null ? List.of() : List.copyOf(reasons), suggestion);
    }
}

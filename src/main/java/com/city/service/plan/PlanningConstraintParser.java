package com.city.service.plan;

import com.city.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把已经结构化的预算槽位转换为 Solver 可消费的明确上限；无法可靠解析时返回 null，避免错误收紧。 */
@Service
public class PlanningConstraintParser {
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    public BigDecimal explicitMaxBudget(SlotBundle slots) {
        if (slots == null || slots.budget() == null || slots.budget().size() != 1) return null;
        String value = slots.budget().getFirst();
        if (value == null || value.isBlank()) return null;
        if (value.contains("免费")) return BigDecimal.ZERO;
        Matcher matcher = NUMBER.matcher(value);
        if (!matcher.find()) return null;
        try {
            return new BigDecimal(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

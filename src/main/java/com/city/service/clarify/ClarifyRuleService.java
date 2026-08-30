package com.city.service.clarify;

import com.city.enums.Intent;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 澄清规则服务。
 * Java 只追问执行任务不可缺少的信息，不为了补全用户画像阻塞推荐。
 */
@Service
public class ClarifyRuleService {

    /**
     * 普通推荐：只要求 city。
     * 行程规划：要求 city + 明确日期；具体时段和其余九维偏好都可缺省。
     */
    public List<String> missingRequiredFields(Intent intent,
                                              SlotBundle slots,
                                              TimeConstraint timeConstraint) {
        SlotBundle safeSlots = slots == null ? SlotBundle.empty() : slots;
        List<String> missing = new ArrayList<>();

        if (safeSlots.city().isEmpty()) {
            missing.add("city");
        }

        if (intent == Intent.ACTIVITY_PLAN
                && (timeConstraint == null || !timeConstraint.hasDate())) {
            missing.add("date");
        }
        return List.copyOf(missing);
    }

    /**
     * 兼容旧调用：按普通推荐规则判断。
     * unconstrainedSlots 不再参与必填判断；city 不能通过“不限”绕过。
     */
    public List<String> missingSlots(SlotBundle slots,
                                     TimeConstraint timeConstraint,
                                     Set<String> unconstrainedSlots) {
        return missingRequiredFields(Intent.MEAL_RECOMMENDATION, slots, timeConstraint);
    }

    /** 固定追问文案，不再调用 LLM。一次只追问一个必要字段。 */
    public String questionFor(List<String> missingFields) {
        if (missingFields == null || missingFields.isEmpty()) {
            throw new IllegalArgumentException("missingFields 不能为空");
        }
        String field = missingFields.getFirst();
        return switch (field) {
            case "city" -> "你想看哪个城市的活动？";
            case "date" -> "你想安排哪一天？比如本周六或本周日。";
            default -> throw new IllegalArgumentException("不支持的澄清字段: " + field);
        };
    }

    /** 兼容旧代码的固定文案入口。 */
    public String fallbackQuestion(List<String> missingSlots, TimeConstraint timeConstraint) {
        return questionFor(missingSlots);
    }
}

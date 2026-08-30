package com.city.service.clarify;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 必要字段澄清规则。
 * Java 只追问执行任务不可缺少的信息，不为了补全偏好画像阻塞推荐。
 */
@Service
public class ClarifyRuleService {

    /**
     * 普通推荐：只要求 city。
     * 行程规划：要求 city + 明确日期；具体时段和其余九维偏好都可缺省。
     */
    public List<ClarifyField> missingRequiredFields(Intent intent,
                                                    SlotBundle slots,
                                                    TimeConstraint timeConstraint) {
        SlotBundle safeSlots = slots == null ? SlotBundle.empty() : slots;
        List<ClarifyField> missing = new ArrayList<>();

        if (safeSlots.city().isEmpty()) {
            missing.add(ClarifyField.CITY);
        }

        if (intent == Intent.ACTIVITY_PLAN
                && (timeConstraint == null || !timeConstraint.hasDate())) {
            missing.add(ClarifyField.DATE);
        }
        return List.copyOf(missing);
    }

    /** 固定追问文案，不调用 LLM。 */
    public String questionFor(ClarifyField field) {
        if (field == null) {
            throw new IllegalArgumentException("clarify field 不能为空");
        }
        return switch (field) {
            case CITY -> "你想看哪个城市的活动？";
            case DATE -> "你想安排哪一天？比如本周六或本周日。";
            case TIME -> "我没能准确理解你的时间要求。可以说得更具体一点吗？例如「下周六下午3点」或「晚上7点到9点」。";
        };
    }
}

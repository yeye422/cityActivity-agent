package com.city.service.clarify;

import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 澄清规则服务。
 * 是否追问由 Java 规则决定，避免 LLM 随机性影响状态机；ClarifyAgent 只负责生成追问文案。
 */
@Service
public class ClarifyRuleService {

    public List<String> missingSlots(SlotBundle slots,
                                     TimeConstraint timeConstraint,
                                     Set<String> unconstrainedSlots) {
        SlotBundle safeSlots = slots == null ? SlotBundle.empty() : slots;
        Set<String> safeUnconstrained = unconstrainedSlots == null ? Set.of() : unconstrainedSlots;
        List<String> missing = new ArrayList<>();

        if (safeSlots.city().isEmpty() && !safeUnconstrained.contains("city")) {
            missing.add("city");
        }

        if (safeSlots.budget().isEmpty()
                && !safeUnconstrained.contains("budget")
                && !hasStrongActivityPreference(safeSlots)) {
            missing.add("budget");
        }
        return missing;
    }

    /** 九维中任一较强偏好已出现时，不因预算缺失机械追问。 */
    private boolean hasStrongActivityPreference(SlotBundle slots) {
        return !slots.activityType().isEmpty()
                || !slots.style().isEmpty()
                || !slots.experienceGoal().isEmpty()
                || !slots.companion().isEmpty()
                || !slots.duration().isEmpty()
                || !slots.feature().isEmpty();
    }

    public String fallbackQuestion(List<String> missingSlots, TimeConstraint timeConstraint) {
        if (missingSlots == null || missingSlots.isEmpty()) {
            return "你更想参加哪类活动，或者更看重什么体验？";
        }
        if (missingSlots.contains("city")) {
            return timeConstraint != null && timeConstraint.hasDate()
                    ? "你想看哪个城市当天的活动？"
                    : "你想看哪个城市的活动？";
        }
        if (missingSlots.contains("budget")) {
            return "预算有偏好吗？不限制也可以，我会优先推荐性价比合适的活动。";
        }
        return "我再确认一下，你更看重活动类型、同行人还是交通便利？";
    }
}

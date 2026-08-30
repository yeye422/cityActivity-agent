package com.city.service.clarify;

import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/**
 * 澄清规则服务。
 * 是否追问由 Java 规则决定，避免 LLM 随机性影响状态机；ClarifyAgent 只负责生成追问文案。
 */
@Service
public class ClarifyRuleService {

    /** 判断当前槽位是否足够进入推荐（missingSlots 为空即足够）。 */
    public boolean hasEnoughSlots(SlotBundle slots) {
        return missingSlots(slots).isEmpty();
    }

    public boolean hasEnoughSlots(SlotBundle slots, TimeConstraint timeConstraint) {
        return missingSlots(slots, timeConstraint).isEmpty();
    }

    /**
     * 计算城市活动推荐所需的最小澄清信息。
     * 城市是推荐前的关键上下文。日期/时段是可选筛选条件：未指定时按活动库的默认可用范围推荐，
     * 日期/时段由 TimeConstraint 单独处理，不依赖活动时间标签。
     */
    public List<String> missingSlots(SlotBundle slots) {
        return missingSlots(slots, TimeConstraint.empty());
    }

    /** 已有绝对日期时不再额外追问时间标签。 */
    public List<String> missingSlots(SlotBundle slots, TimeConstraint timeConstraint) {
        // slots 为 null 时用空 SlotBundle 代替
        SlotBundle safeSlots = slots == null ? SlotBundle.empty() : slots;
        List<String> missing = new ArrayList<>();
        if (safeSlots.city().isEmpty()) {
            missing.add("city");
        }
        // 真实日期由 TimeConstraint 单独处理。
        // budget 在 City-Agent 中表示预算；已有活动类型/风格/同行场景时可缺省
        if (safeSlots.budget().isEmpty() && !hasStrongActivityPreference(safeSlots)) {
            missing.add("budget");
        }
        return missing;
    }

    /** 用户已明确活动类型、风格、同行场景或活动时长时，预算可缺省。 */
    private boolean hasStrongActivityPreference(SlotBundle slots) {
        return !slots.activityType().isEmpty()
                || !slots.style().isEmpty()
                || !slots.scene().isEmpty()
                || !slots.duration().isEmpty();
    }

    /** LLM 澄清失败或返回空时的模板追问文案，按 missingSlots 内容选择。 */
    public String fallbackQuestion(List<String> missingSlots) {
        return fallbackQuestion(missingSlots, TimeConstraint.empty());
    }

    /** 使用已解析日期生成确定性追问，避免 LLM 将“明天”错误说成“周末”。 */
    public String fallbackQuestion(List<String> missingSlots, TimeConstraint timeConstraint) {
        if (missingSlots == null || missingSlots.isEmpty()) {
            return "你更想参加哪类活动，预算大概是多少？";
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

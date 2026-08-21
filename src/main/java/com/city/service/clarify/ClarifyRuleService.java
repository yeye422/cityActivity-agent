package com.city.service.clarify;

import com.city.model.SlotBundle;
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

    /**
     * 计算城市活动推荐所需的最小澄清信息。
     * city 和 activityTime 是推荐前的关键上下文；location 是进一步缩小范围的可选条件。
     */
    public List<String> missingSlots(SlotBundle slots) {
        // slots 为 null 时用空 SlotBundle 代替
        SlotBundle safeSlots = slots == null ? SlotBundle.empty() : slots;
        List<String> missing = new ArrayList<>();
        if (safeSlots.city().isEmpty()) {
            missing.add("city");
        }
        // activityTime 在 City-Agent 中表示活动时间
        if (safeSlots.activityTime().isEmpty()) {
            missing.add("activityTime");
        }
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
        if (missingSlots == null || missingSlots.isEmpty()) {
            return "你更想参加哪类活动，预算大概是多少？";
        }
        if (missingSlots.contains("city")) {
            return "你想看哪个城市的周末活动？";
        }
        if (missingSlots.contains("activityTime")) {
            return "你计划什么时候参加活动？周六白天、周六晚上，还是周日？";
        }
        if (missingSlots.contains("budget")) {
            return "预算大概是多少？可以是免费、100 元内或 200 元内。";
        }
        return "我再确认一下，你更看重活动类型、同行人还是交通便利？";
    }
}

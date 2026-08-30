package com.city.service.clarify;

import com.city.enums.Intent;
import com.city.model.ClarifyResult;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Java 澄清服务。
 * 不再调用 LLM；必填字段判断与追问文案都由确定性规则生成。
 */
@Service
public class ClarifyAgentService {

    private final ClarifyRuleService clarifyRuleService;

    public ClarifyAgentService(ClarifyRuleService clarifyRuleService) {
        this.clarifyRuleService = clarifyRuleService;
    }

    /** 普通推荐兼容入口：只要求 city。 */
    public ClarifyResult decide(String sessionId,
                                String userInput,
                                SlotBundle slots,
                                TimeConstraint timeConstraint,
                                Set<String> unconstrainedSlots) {
        return decide(Intent.MEAL_RECOMMENDATION, slots, timeConstraint);
    }

    /** 按当前业务意图执行必要字段澄清。 */
    public ClarifyResult decide(Intent intent,
                                SlotBundle slots,
                                TimeConstraint timeConstraint) {
        List<String> missingFields = clarifyRuleService.missingRequiredFields(intent, slots, timeConstraint);
        if (missingFields.isEmpty()) {
            return ClarifyResult.ready();
        }
        return ClarifyResult.ask(clarifyRuleService.questionFor(missingFields), missingFields);
    }
}

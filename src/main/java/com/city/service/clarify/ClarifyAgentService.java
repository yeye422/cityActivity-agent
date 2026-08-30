package com.city.service.clarify;

import com.city.enums.Intent;
import com.city.model.ClarifyResult;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java 澄清服务。
 * 类名为兼容现有 Orchestrator 保留；内部已经没有 Agent/LLM 调用。
 */
@Service
public class ClarifyAgentService {

    private final ClarifyRuleService clarifyRuleService;

    /**
     * 兼容现有 Orchestrator 调用签名，记录正在进行的 Plan 澄清链。
     * 后续应把该标记并入持久化 SessionState，避免服务重启后丢失。
     */
    private final Set<String> pendingPlanClarifications = ConcurrentHashMap.newKeySet();

    public ClarifyAgentService(ClarifyRuleService clarifyRuleService) {
        this.clarifyRuleService = clarifyRuleService;
    }

    public ClarifyResult decide(String sessionId,
                                String userInput,
                                SlotBundle slots,
                                TimeConstraint timeConstraint,
                                Set<String> unconstrainedSlots) {
        boolean planTurn = pendingPlanClarifications.contains(sessionId) || containsPlanSignal(userInput);
        Intent intent = planTurn ? Intent.ACTIVITY_PLAN : Intent.MEAL_RECOMMENDATION;
        ClarifyResult result = decide(intent, slots, timeConstraint);

        if (intent == Intent.ACTIVITY_PLAN && result.action() == com.city.enums.ClarifyAction.ASK) {
            pendingPlanClarifications.add(sessionId);
        } else {
            pendingPlanClarifications.remove(sessionId);
        }
        return result;
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

    private boolean containsPlanSignal(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        String text = userInput.replaceAll("\\s+", "");
        return containsAny(text,
                "活动规划", "一日行程", "半日行程", "行程",
                "帮我安排", "给我安排", "帮我规划", "给我规划",
                "安排一下", "规划一下", "排一下", "怎么安排", "如何安排",
                "周末安排", "从上午到晚上", "从早到晚");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}

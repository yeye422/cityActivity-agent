package com.city.service.intent;

import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TemporalMutation;
import org.springframework.stereotype.Service;

/**
 * 意图后处理服务。
 * LLM 意图识别可能误判，Orchestrator 在路由前用历史 SessionState 做二次矫正。
 */
@Service
public class IntentReviseService {

    /** 低于该阈值时，推荐意图降级为澄清，避免低确定性结果直接进入推荐。 */
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.4;

    /**
     * 根据会话状态矫正 IntentAgent 输出。
     * 由 Orchestrator#handleTurn 在 INTENT_RECOGNIZED 之后调用。
     */
    public IntentResult revise(SessionState state, IntentResult result, String userInput) {
        IntentResult safeResult = result == null ? IntentResult.clarify(SlotBundle.empty()) : result;

        if (safeResult.intent() == Intent.HEALTH_RISK || containsSafetyRiskKeyword(userInput)) {
            return revised(Intent.HEALTH_RISK, safeResult);
        }

        if (state != null
                && state.phase() == SessionPhase.CLARIFY
                && state.currentIntent() == Intent.ACTIVITY_PLAN
                && (safeResult.intent() == Intent.MEAL_RECOMMENDATION || safeResult.intent() == Intent.CLARIFY_NEEDED)) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        if (safeResult.intent() == Intent.MEAL_ADJUST && !hasLastRecommendations(state)) {
            return revised(Intent.MEAL_RECOMMENDATION, safeResult);
        }

        if (containsActivityPlanKeyword(userInput)
                && safeResult.intent() != Intent.MEAL_ADJUST
                && safeResult.intent() != Intent.ACTIVITY_PLAN) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        if (safeResult.intent() == Intent.MEAL_RECOMMENDATION && safeResult.confidence() < LOW_CONFIDENCE_THRESHOLD) {
            return revised(Intent.CLARIFY_NEEDED, safeResult);
        }

        return safeResult;
    }

    private boolean hasLastRecommendations(SessionState state) {
        return state != null && state.lastRecommendedActivityIds() != null && !state.lastRecommendedActivityIds().isEmpty();
    }

    private SlotBundle safeSlots(IntentResult result) {
        return result.slots() == null ? SlotBundle.empty() : result.slots();
    }

    private IntentResult revised(Intent intent, IntentResult result) {
        return new IntentResult(
                intent,
                safeSlots(result),
                result.confidence(),
                result.operations() == null ? java.util.List.of() : result.operations(),
                result.temporal() == null ? TemporalMutation.keep() : result.temporal()
        );
    }

    private boolean containsSafetyRiskKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        return containsAny(userInput, "深夜独自", "凌晨一个人", "偏远", "无人区", "危险活动", "极端天气", "暴雨", "台风");
    }

    private boolean containsActivityPlanKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        return containsAny(userInput, "半天", "一天", "一日", "活动规划", "行程", "安排一下", "周末安排");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

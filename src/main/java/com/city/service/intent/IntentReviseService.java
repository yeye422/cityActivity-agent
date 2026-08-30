package com.city.service.intent;

import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
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
        // result 为 null 时构造 CLARIFY_NEEDED + 空槽位，防止 NPE
        IntentResult safeResult = result == null ? IntentResult.clarify(SlotBundle.empty()) : result;

        // 规则一：活动安全风险优先前置拦截，即使 LLM 置信度较低也走保守风险链路
        if (safeResult.intent() == Intent.HEALTH_RISK || containsSafetyRiskKeyword(userInput)) {
            return revised(Intent.HEALTH_RISK, safeResult);
        }

        // 规则二：规划请求在澄清阶段需要保留原规划意图。
        // 例如第一轮“帮我安排一天行程”因缺少城市进入 CLARIFY，下一轮用户只回复“西安”时，
        // IntentAgent 往往只能识别成普通推荐/继续补充条件，此处根据持久化会话状态恢复 ACTIVITY_PLAN。
        if (state != null
                && state.phase() == SessionPhase.CLARIFY
                && state.currentIntent() == Intent.ACTIVITY_PLAN
                && (safeResult.intent() == Intent.MEAL_RECOMMENDATION || safeResult.intent() == Intent.CLARIFY_NEEDED)) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        // 规则三：没有历史推荐时，调整意图没有可排除对象，降级为推荐主链路并由澄清规则继续判断
        if (safeResult.intent() == Intent.MEAL_ADJUST && !hasLastRecommendations(state)) {
            return revised(Intent.MEAL_RECOMMENDATION, safeResult);
        }

        // 规则四：含半日或一日安排关键词时强制进入活动规划分支
        if (containsActivityPlanKeyword(userInput)
                && safeResult.intent() != Intent.MEAL_ADJUST
                && safeResult.intent() != Intent.ACTIVITY_PLAN) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        // 规则四：推荐意图低置信度时先进入澄清链路；健康风险已在上方优先处理，不在这里降级
        if (safeResult.intent() == Intent.MEAL_RECOMMENDATION && safeResult.confidence() < LOW_CONFIDENCE_THRESHOLD) {
            return revised(Intent.CLARIFY_NEEDED, safeResult);
        }

        // 无矫正规则命中，原样返回 LLM 结果
        return safeResult;
    }

    /** 判断会话是否已有可用于“换一批”的上轮推荐结果。 */
    private boolean hasLastRecommendations(SessionState state) {
        return state != null && state.lastRecommendedActivityIds() != null && !state.lastRecommendedActivityIds().isEmpty();
    }

    /** slots 为空时使用空槽位，避免后续合并逻辑出现 NPE。 */
    private SlotBundle safeSlots(IntentResult result) {
        return result.slots() == null ? SlotBundle.empty() : result.slots();
    }

    private IntentResult revised(Intent intent, IntentResult result) {
        return new IntentResult(intent, safeSlots(result), result.confidence(),
                result.operations() == null ? java.util.List.of() : result.operations());
    }

    /** 活动安全关键词命中时，Java 规则直接前置拦截。 */
    private boolean containsSafetyRiskKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        return containsAny(userInput, "深夜独自", "凌晨一个人", "偏远", "无人区", "危险活动", "极端天气", "暴雨", "台风");
    }

    /** 半日或一日活动安排关键词命中时，矫正为 ACTIVITY_PLAN。 */
    private boolean containsActivityPlanKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        return containsAny(userInput, "半天", "一天", "一日", "活动规划", "行程", "安排一下", "周末安排");
    }

    /** 判断 text 是否包含 keywords 中任一子串。 */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

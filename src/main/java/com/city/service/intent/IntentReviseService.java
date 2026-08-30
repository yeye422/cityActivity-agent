package com.city.service.intent;

import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.SlotBundle;
import com.city.model.TemporalMutation;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图后处理服务。
 * 只负责根据历史会话状态修正 intent；时间解析与时间状态合并由 Orchestrator 的时间链路单独处理。
 */
@Service
public class IntentReviseService {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.4;
    private static final double BATCH_REFRESH_CONFIDENCE = 0.95;

    public IntentResult revise(SessionState state, IntentResult result, String userInput) {
        IntentResult safeResult = result == null ? IntentResult.clarify(SlotBundle.empty()) : result;

        if (safeResult.intent() == Intent.HEALTH_RISK || containsSafetyRiskKeyword(userInput)) {
            return revised(Intent.HEALTH_RISK, safeResult);
        }

        // “换一批”只刷新结果集，不应该修改任何普通槽位或时间约束。
        // 即使 LLM 误输出 CLEAR/SET/ADD/REMOVE，也在这里归一化为空 Patch，避免 QueryKey 被错误改变。
        if (isPureBatchRefresh(userInput)) {
            Intent targetIntent = hasLastRecommendations(state)
                    ? Intent.MEAL_ADJUST
                    : Intent.MEAL_RECOMMENDATION;
            return batchRefresh(targetIntent, safeResult);
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

    /**
     * 仅识别“纯结果刷新”表达。
     * 像“换一批室内的”“再来几个便宜点的”同时包含新约束，不在这里拦截，继续交给正常 Patch 链路处理。
     */
    private boolean isPureBatchRefresh(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        String text = userInput
                .replaceAll("\\s+", "")
                .replaceAll("[，。！？,.!?~～]", "");
        return List.of(
                "换一批",
                "换一批吧",
                "再换一批",
                "再换一批吧",
                "换几个",
                "换几个吧",
                "换几个看看",
                "再来几个",
                "再来几个吧",
                "再推荐几个",
                "再推荐几个吧",
                "还有别的吗",
                "还有别的么",
                "还有其他的吗",
                "还有其他的么",
                "换点别的",
                "换点别的吧"
        ).contains(text);
    }

    /** 纯换批必须清空模型产生的 slots/operations/temporal 变更，只保留结果刷新意图。 */
    private IntentResult batchRefresh(Intent intent, IntentResult result) {
        return new IntentResult(
                intent,
                SlotBundle.empty(),
                Math.max(result.confidence(), BATCH_REFRESH_CONFIDENCE),
                List.of(),
                TemporalMutation.keep()
        );
    }

    private SlotBundle safeSlots(IntentResult result) {
        return result.slots() == null ? SlotBundle.empty() : result.slots();
    }

    private IntentResult revised(Intent intent, IntentResult result) {
        return new IntentResult(
                intent,
                safeSlots(result),
                result.confidence(),
                result.operations() == null ? List.of() : result.operations(),
                result.temporal() == null ? TemporalMutation.keep() : result.temporal()
        );
    }

    private boolean containsSafetyRiskKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        return containsAny(userInput, "深夜独自", "凌晨一个人", "偏远", "无人区", "危险活动", "极端天气", "暴雨", "台风");
    }

    private boolean containsActivityPlanKeyword(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        return containsAny(userInput, "半天", "一天", "一日", "活动规划", "行程", "安排一下", "周末安排");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}

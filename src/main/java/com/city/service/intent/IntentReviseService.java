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
 * 正常模型成功路径不再用关键词重新判断用户语义；这里只保留确定性的会话状态修正。
 */
@Service
public class IntentReviseService {

    private static final double BATCH_REFRESH_CONFIDENCE = 0.95;

    public IntentResult revise(SessionState state, IntentResult result, String userInput) {
        IntentResult safeResult = result == null ? IntentResult.clarify(SlotBundle.empty()) : result;

        // “换一批”是确定性的结果集操作，不允许模型顺带修改普通槽位或时间条件。
        if (isPureBatchRefresh(userInput)) {
            Intent targetIntent = hasLastRecommendations(state)
                    ? Intent.MEAL_ADJUST
                    : Intent.MEAL_RECOMMENDATION;
            return batchRefresh(targetIntent, safeResult);
        }

        // 正在回答持久化的 Plan 必要字段时，短回答沿用原 Plan 意图。
        if (state != null
                && state.phase() == SessionPhase.CLARIFY
                && state.pendingClarifyField() != null
                && state.currentIntent() == Intent.ACTIVITY_PLAN
                && (safeResult.intent() == Intent.MEAL_RECOMMENDATION || safeResult.intent() == Intent.CLARIFY_NEEDED)) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        // 没有历史推荐结果时，ADJUST 不存在可调整对象，按首次推荐流程处理。
        if (safeResult.intent() == Intent.MEAL_ADJUST && !hasLastRecommendations(state)) {
            return revised(Intent.MEAL_RECOMMENDATION, safeResult);
        }

        // 其他情况下尊重模型判断：
        // 不再做 HEALTH_RISK 关键词覆盖、Plan 正向/反向关键词纠正或低 confidence 强制澄清。
        return safeResult;
    }

    private boolean hasLastRecommendations(SessionState state) {
        return state != null
                && state.lastRecommendedActivityIds() != null
                && !state.lastRecommendedActivityIds().isEmpty();
    }

    /**
     * 仅识别“纯结果刷新”表达。
     * “换一批室内的”“再来几个便宜点的”包含新约束，不在这里归一化。
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
                TemporalMutation.keep(),
                result.fallback()
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
                result.temporal() == null ? TemporalMutation.keep() : result.temporal(),
                result.fallback()
        );
    }
}

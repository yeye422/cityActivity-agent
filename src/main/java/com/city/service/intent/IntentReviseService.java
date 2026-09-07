package com.city.service.intent;

import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.model.TemporalMutation;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * IntentAgent 结果的确定性后处理层。
 *
 * <p>边界非常重要：这里不重新理解自然语言，也不使用关键词覆盖模型的普通语义判断；
 * 只根据系统已经确定的会话事实或 fallback 已经抽取出的结构化 Patch，修正不可能成立的路由。</p>
 *
 * <p>主链路因此保持为：LLM 负责业务意图理解 → 本服务做状态一致性修正 → Orchestrator 按最终 Intent 路由。
 * 澄清和安全风险都由后端独立处理，不属于 IntentRevise 的职责。</p>
 */
@Service
public class IntentReviseService {

    /** 纯“换一批”属于确定性结果集操作，修正后的置信度至少提升到该值。 */
    private static final double BATCH_REFRESH_CONFIDENCE = 0.95;

    /**
     * 根据持久化会话状态修正 IntentAgent 输出。
     *
     * <p>当前处理四类确定性场景：纯换批、Plan 澄清续答、无历史结果的 ADJUST 降级为首次推荐，
     * 以及模型失败后已经抽取到普通槽位 Patch 却误落 OTHER 的 fallback 路由修正。</p>
     *
     * @param state 当前持久化会话状态
     * @param result IntentAgent 的结构化输出
     * @param userInput 本轮用户原文，仅用于识别严格限定的“纯换一批”表达
     * @return 可直接交给 Orchestrator 路由的最终意图结果
     */
    public IntentResult revise(SessionState state, IntentResult result, String userInput) {
        // 正常情况下 IntentAgent 已经提供 fallback；异常 null 再保守落到推荐业务，由后端必要字段规则决定是否追问。
        IntentResult safeResult = result == null ? IntentResult.fallbackRecommendation() : result;

        //        用户说“换一批”
        //→ IntentAgent 理想输出 MEAL_ADJUST
        //→ IntentReviseService.isPureBatchRefresh(userInput)
        //→ Java 再次确认这是纯换批
        //→ 强制清空 slots / operations / temporal
        //→ 只保留“刷新结果集”
        // “换一批”是确定性的结果集操作，不允许模型顺带修改普通槽位或时间条件。
        if (isPureBatchRefresh(userInput)) {
            Intent targetIntent = hasLastRecommendations(state)
                    ? Intent.MEAL_ADJUST
                    : Intent.MEAL_RECOMMENDATION;
            return batchRefresh(targetIntent, safeResult);
        }

        // 正在回答持久化的 Plan 必要字段时，短回答沿用原 Plan 意图。
        // 模型正常识别成推荐，或模型失败后保守落到 OTHER，都不能让已确定的 Plan 澄清上下文丢失。
        if (state != null
                && state.phase() == SessionPhase.CLARIFY
                && state.pendingClarifyField() != null
                && state.currentIntent() == Intent.ACTIVITY_PLAN
                && (safeResult.intent() == Intent.MEAL_RECOMMENDATION
                    || (safeResult.fallback() && safeResult.intent() == Intent.OTHER))) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        // 没有历史推荐结果时，ADJUST 不存在可调整对象，按首次推荐流程处理。
        if (safeResult.intent() == Intent.MEAL_ADJUST && !hasLastRecommendations(state)) {
            return revised(Intent.MEAL_RECOMMENDATION, safeResult);
        }

        // 模型调用失败时，Java fallback 可能已经可靠抽取出 CLEAR/ADD/REMOVE 等普通槽位 Patch。
        // 此时若 fallbackIntent 只能落到 OTHER，不能丢弃已经确定的业务修改：有历史推荐按 ADJUST，没有则按首次推荐。
        if (safeResult.fallback()
                && safeResult.intent() == Intent.OTHER
                && safeResult.operations() != null
                && !safeResult.operations().isEmpty()) {
            Intent targetIntent = hasLastRecommendations(state)
                    ? Intent.MEAL_ADJUST
                    : Intent.MEAL_RECOMMENDATION;
            return revised(targetIntent, safeResult);
        }

        // 其他情况下尊重模型判断，不再做关键词意图覆盖或低 confidence 强制改路由。
        return safeResult;
    }

    /** 是否存在可供“调整/换一批”继续操作的上一轮推荐结果。 */
    private boolean hasLastRecommendations(SessionState state) {
        return state != null
                && state.lastRecommendedActivityIds() != null
                && !state.lastRecommendedActivityIds().isEmpty();
    }

    /**
     * 仅识别“纯结果刷新”表达。
     * “换一批室内的”“再来几个便宜点的”包含新约束，不在这里归一化，必须交给 IntentAgent 解析。
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

    /**
     * 纯换批必须清空模型产生的 operations / temporal 变更，只保留“刷新结果集”这一件事。
     * 否则模型偶然抽出的约束 Patch 会污染上一轮条件，导致“换一批”实际变成“修改条件后重搜”。
     */
    private IntentResult batchRefresh(Intent intent, IntentResult result) {
        return new IntentResult(
                intent,
                Math.max(result.confidence(), BATCH_REFRESH_CONFIDENCE),
                List.of(),
                TemporalMutation.keep(),
                result.fallback()
        );
    }

    /**
     * 只替换 Intent，本轮已经识别出的 operations / temporal 保持不变。
     * 用于“语义基本正确，但受会话状态约束需要切换路由”的场景。
     */
    private IntentResult revised(Intent intent, IntentResult result) {
        return new IntentResult(
                intent,
                result.confidence(),
                result.operations() == null ? List.of() : result.operations(),
                result.temporal() == null ? TemporalMutation.keep() : result.temporal(),
                result.fallback()
        );
    }
}

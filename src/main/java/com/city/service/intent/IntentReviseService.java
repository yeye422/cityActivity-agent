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

        // Plan 澄清中的短回答（如“上海”“预算不限”）必须继续沿用原 Plan 上下文，不能掉回普通推荐。
        if (state != null
                && state.phase() == SessionPhase.CLARIFY
                && state.currentIntent() == Intent.ACTIVITY_PLAN
                && (safeResult.intent() == Intent.MEAL_RECOMMENDATION || safeResult.intent() == Intent.CLARIFY_NEEDED)) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        if (safeResult.intent() == Intent.MEAL_ADJUST && !hasLastRecommendations(state)) {
            return revised(Intent.MEAL_RECOMMENDATION, safeResult);
        }

        // 规划动作词是 Java 强兜底信号；“半天/一天/全天”本身只是时长或可用时间，不能单独触发 Plan。
        if (containsActivityPlanSignal(userInput)
                && safeResult.intent() != Intent.MEAL_ADJUST
                && safeResult.intent() != Intent.ACTIVITY_PLAN) {
            return revised(Intent.ACTIVITY_PLAN, safeResult);
        }

        // 反向保护：模型若把“找个半天展览”“周六全天都行”这类单活动/可用时间表达误判为 Plan，
        // 在没有任何规划动作词时纠正回普通推荐。
        if (safeResult.intent() == Intent.ACTIVITY_PLAN
                && !containsActivityPlanSignal(userInput)
                && looksLikeSingleActivityOrAvailabilityRequest(userInput)) {
            return revised(Intent.MEAL_RECOMMENDATION, safeResult);
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

    /**
     * Java 只对明确的“规划动作”做强兜底，不再把“半天/一天/一日/全天”当成 Plan 关键词。
     * 时长词只有和“安排/规划/行程/排一下”等动作语义结合时才进入多时段规划。
     */
    private boolean containsActivityPlanSignal(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        String text = userInput.replaceAll("\\s+", "");
        return containsAny(text,
                "活动规划",
                "一日行程",
                "半日行程",
                "行程",
                "帮我安排",
                "给我安排",
                "帮我规划",
                "给我规划",
                "安排一下",
                "规划一下",
                "排一下",
                "怎么安排",
                "如何安排",
                "周末安排",
                "从上午到晚上",
                "从早到晚");
    }

    /**
     * 没有规划动作时，以下表达更像“找一个活动/补充可用时间”，用于纠正模型把时长词误判成 Plan。
     */
    private boolean looksLikeSingleActivityOrAvailabilityRequest(String userInput) {
        if (userInput == null || userInput.isBlank()) return false;
        String text = userInput.replaceAll("\\s+", "");
        boolean hasDurationOrAvailability = containsAny(text,
                "半天", "一天", "一日", "全天", "一整天", "都有空", "都可以", "都行");
        boolean hasRecommendationContext = containsAny(text,
                "找个", "想找", "推荐", "有没有", "活动", "项目", "展览", "电影", "演出", "运动", "探店", "去哪", "玩什么");
        return hasDurationOrAvailability && (hasRecommendationContext || containsAny(text, "有空", "都可以", "都行"));
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}

package com.city.service.risk;

import com.city.model.RiskGuardResult;
import com.city.enums.Intent;
import com.city.model.RecommendResult;
import com.city.model.ResponseResult;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * 城市活动安全风险守卫。
 * 在 Orchestrator#completeRecommendation 中，LLM 生成回复后做最后一道合规检查。
 */
@Component
public class RiskGuardService {

    /**
     * 检查用户输入 + 最终回复是否含健康风险表述。
     * 命中任一规则返回 block，Orchestrator 用 conservativeMessage 替换 speechText。
     */
    public RiskGuardResult check(String userInput, Intent intent, RecommendResult recommendResult, ResponseResult responseResult) {
        List<String> reasons = new ArrayList<>();
        // 拼接用户原文和助手回复，统一扫描关键词
        String allText = (userInput == null ? "" : userInput) + " " + (responseResult == null ? "" : responseResult.speechText());
        // 规则 1：意图层已识别为安全风险
        if (intent == Intent.HEALTH_RISK) {
            reasons.add("命中活动安全风险意图");
        }
        // 规则 2：夜间独行、偏远地点和明显危险活动
        if (containsAny(allText, "深夜独自", "凌晨一个人", "偏远", "无人区", "危险活动")) {
            reasons.add("涉及夜间独行或高风险地点");
        }
        // 规则 3：极端天气下的户外活动
        if (containsAny(allText, "暴雨", "台风", "雷暴", "极端高温", "极端天气") && containsAny(allText, "户外", "爬山", "露营", "徒步")) {
            reasons.add("极端天气下的户外活动风险");
        }
        // 规则 4：不允许对活动安全作绝对承诺
        if (containsAny(allText, "绝对安全", "保证不会", "百分之百安全")) {
            reasons.add("涉及活动安全绝对化承诺");
        }
        // 规则 5：未成年人进入不适宜场所
        if (containsAny(allText, "未成年人", "小朋友", "儿童") && containsAny(allText, "酒吧", "夜店", "成人场所")) {
            reasons.add("涉及未成年人不适宜场所");
        }
        // 无命中规则 → 通过
        if (reasons.isEmpty()) {
            return RiskGuardResult.pass();
        }
        // 有命中 → 拦截，返回 reasons 和 conservativeMessage 作为 rewriteSuggestion
        return RiskGuardResult.block(reasons, conservativeMessage());
    }

    /** 高风险场景的保守固定提示文案。 */
    public String conservativeMessage() {
        return "这个活动可能存在安全或适龄风险，我不能保证活动绝对安全。建议优先选择正规场所，关注天气、交通和开放时间；如涉及未成年人或特殊情况，请先确认场所规则。";
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


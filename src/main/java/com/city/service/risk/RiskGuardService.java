package com.city.service.risk;

import com.city.model.RiskGuardResult;
import com.city.model.ResponseResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 城市活动安全风险守卫。
 *
 * <p>安全风险不再作为 Intent 参与业务路由。本服务作为后端横切守卫：
 * 路由前检查用户原文，阻断明显高风险请求；推荐/规划生成后再次检查最终回复，
 * 防止模型产生不安全或绝对化的活动建议。</p>
 */
@Component
public class RiskGuardService {

    /** 路由前只检查用户原文，避免风险请求进入普通搜索/规划链路。 */
    public RiskGuardResult checkInput(String userInput) {
        return evaluate(userInput == null ? "" : userInput);
    }

    /**
     * 生成后检查用户输入 + 最终回复。
     * 命中任一规则返回 block，Orchestrator 用保守提示替换最终 speechText。
     */
    public RiskGuardResult check(String userInput, ResponseResult responseResult) {
        String allText = (userInput == null ? "" : userInput)
                + " "
                + (responseResult == null ? "" : responseResult.speechText());
        return evaluate(allText);
    }

    private RiskGuardResult evaluate(String text) {
        List<String> reasons = new ArrayList<>();
        String safeText = text == null ? "" : text;

        // 夜间独行、偏远地点和明显危险活动。
        if (containsAny(safeText, "深夜独自", "凌晨一个人", "偏远", "无人区", "危险活动")) {
            reasons.add("涉及夜间独行或高风险地点");
        }

        // 极端天气下的户外活动。
        if (containsAny(safeText, "暴雨", "台风", "雷暴", "极端高温", "极端天气")
                && containsAny(safeText, "户外", "爬山", "露营", "徒步")) {
            reasons.add("涉及极端天气下的户外活动风险");
        }

        // 明显违法或高风险交通/进入行为。
        if (containsAny(safeText, "酒后驾驶", "酒驾", "醉驾", "翻越围栏", "违法进入", "擅闯")) {
            reasons.add("涉及违法或高风险行为");
        }

        // 不允许对活动安全作绝对承诺。
        if (containsAny(safeText, "绝对安全", "保证不会", "百分之百安全")) {
            reasons.add("涉及活动安全绝对化承诺");
        }

        // 未成年人进入不适宜场所。
        if (containsAny(safeText, "未成年人", "小朋友", "儿童")
                && containsAny(safeText, "酒吧", "夜店", "成人场所")) {
            reasons.add("涉及未成年人不适宜场所");
        }

        if (reasons.isEmpty()) {
            return RiskGuardResult.pass();
        }
        return RiskGuardResult.block(reasons, conservativeMessage());
    }

    /** 高风险场景的统一保守提示文案。 */
    public String conservativeMessage() {
        return "这个请求涉及活动安全风险。请不要在饮酒后驾车，也不要擅自进入受限区域；遇到暴雨、台风、雷暴等极端天气时，应避免爬山、徒步、露营等户外活动。请优先选择正规开放场所，并遵守天气、交通和场所规则。";
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

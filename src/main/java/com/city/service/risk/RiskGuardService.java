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
 * 路由前检查用户原文，阻断明确要求高风险行为的请求；推荐/规划生成后再次检查最终回复，
 * 防止模型产生危险建议或绝对化安全承诺。</p>
 */
@Component
public class RiskGuardService {

    /** 路由前检查用户原文；明确表达“避免风险”的安全约束不应被误判为危险请求。 */
    public RiskGuardResult checkInput(String userInput) {
        String text = userInput == null ? "" : userInput;
        List<String> reasons = new ArrayList<>();

        boolean avoidingRemote = containsAny(text,
                "不要偏远", "不去偏远", "避免偏远", "别去偏远",
                "不要无人区", "不去无人区", "远离无人区");
        if (containsAny(text, "深夜独自", "凌晨一个人", "危险活动")
                || (!avoidingRemote && containsAny(text, "偏远", "无人区"))) {
            reasons.add("涉及夜间独行或高风险地点");
        }

        boolean avoidingOutdoorRisk = containsAny(text,
                "不要户外", "不去户外", "避免户外", "别去户外",
                "不要爬山", "不爬山", "避免爬山",
                "不要徒步", "不徒步", "避免徒步",
                "不要露营", "不露营", "避免露营",
                "改成室内", "推荐室内", "找室内");
        if (!avoidingOutdoorRisk
                && containsAny(text, "暴雨", "台风", "雷暴", "极端高温", "极端天气")
                && containsAny(text, "户外", "爬山", "露营", "徒步")) {
            reasons.add("涉及极端天气下的户外活动风险");
        }

        boolean rejectingIllegalBehavior = containsAny(text,
                "不要酒后驾驶", "不能酒后驾驶", "不酒驾", "不要酒驾", "避免酒驾",
                "不要醉驾", "不能醉驾", "不翻越围栏", "不要翻越围栏", "不擅闯", "不要擅闯");
        if (!rejectingIllegalBehavior
                && containsAny(text, "酒后驾驶", "酒驾", "醉驾", "翻越围栏", "违法进入", "擅闯")) {
            reasons.add("涉及违法或高风险行为");
        }

        boolean avoidingAdultVenue = containsAny(text,
                "不要去酒吧", "不去酒吧", "避免酒吧",
                "不要去夜店", "不去夜店", "避免夜店");
        if (!avoidingAdultVenue
                && containsAny(text, "未成年人", "小朋友", "儿童")
                && containsAny(text, "酒吧", "夜店", "成人场所")) {
            reasons.add("涉及未成年人不适宜场所");
        }

        return result(reasons);
    }

    /**
     * 生成后检查最终回复。用户原文已经由 checkInput 处理，这里只关注模型是否输出危险建议或绝对化承诺。
     * userInput 参数保留是为了兼容现有调用签名，不参与第二次关键词判定。
     */
    public RiskGuardResult check(String userInput, ResponseResult responseResult) {
        String text = responseResult == null || responseResult.speechText() == null
                ? ""
                : responseResult.speechText();
        List<String> reasons = new ArrayList<>();

        if (containsAny(text, "绝对安全", "保证不会", "百分之百安全")) {
            reasons.add("涉及活动安全绝对化承诺");
        }

        if (containsAny(text,
                "可以酒后驾驶", "可以酒驾", "酒后也能开车", "可以醉驾",
                "可以翻越围栏", "可以擅闯", "可以违法进入")) {
            reasons.add("生成内容包含违法或高风险行为建议");
        }

        if (containsAny(text, "暴雨", "台风", "雷暴", "极端高温", "极端天气")
                && containsAny(text,
                "可以爬山", "可以徒步", "可以露营", "照常爬山", "照常徒步", "放心爬山", "放心徒步")) {
            reasons.add("生成内容鼓励极端天气下的户外活动");
        }

        if (containsAny(text, "未成年人", "小朋友", "儿童")
                && containsAny(text, "适合去酒吧", "可以去酒吧", "推荐酒吧", "适合去夜店", "可以去夜店", "推荐夜店")) {
            reasons.add("生成内容推荐未成年人进入不适宜场所");
        }

        return result(reasons);
    }

    private RiskGuardResult result(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
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

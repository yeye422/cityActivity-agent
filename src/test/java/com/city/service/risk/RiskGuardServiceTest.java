package com.city.service.risk;

import com.city.model.ResponseResult;
import com.city.model.RiskGuardResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskGuardServiceTest {

    private final RiskGuardService service = new RiskGuardService();

    @Test
    void shouldBlockExtremeWeatherOutdoorRequestBeforeBusinessRouting() {
        RiskGuardResult result = service.checkInput("暴雨天想去爬山");

        assertFalse(result.passed());
        assertTrue(result.blockedReasons().stream().anyMatch(reason -> reason.contains("极端天气")));
    }

    @Test
    void shouldAllowSafeAlternativeRequestMentioningRiskWords() {
        RiskGuardResult result = service.checkInput("暴雨天不要户外，推荐几个室内展览");

        assertTrue(result.passed());
    }

    @Test
    void shouldBlockDrunkDrivingSafetyRequestWithoutSafetyIntent() {
        RiskGuardResult result = service.checkInput("酒后驾驶去参加活动可以吗？");

        assertFalse(result.passed());
        assertTrue(result.blockedReasons().stream().anyMatch(reason -> reason.contains("违法或高风险")));
    }

    @Test
    void shouldAllowNormalActivityRequest() {
        RiskGuardResult result = service.checkInput("周六在西安看展览");

        assertTrue(result.passed());
    }

    @Test
    void shouldBlockUnsafeAbsoluteClaimInGeneratedResponse() {
        ResponseResult response = ResponseResult.textOnly("这个路线绝对安全，可以放心去。\n");

        RiskGuardResult result = service.check("周六去徒步", response);

        assertFalse(result.passed());
        assertTrue(result.blockedReasons().stream().anyMatch(reason -> reason.contains("绝对化承诺")));
    }
}

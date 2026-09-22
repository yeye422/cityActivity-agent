package com.city.service.intent;

import com.city.enums.Intent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentTaxonomyTest {

    @Test
    void intentShouldContainOnlyBusinessRoutes() {
        Set<String> names = Arrays.stream(Intent.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "ACTIVITY_RECOMMENDATION",
                "ACTIVITY_ADJUST",
                "ACTIVITY_PLAN",
                "OTHER"
        ), names);
    }

    @Test
    void fallbackShouldKeepRiskyRecommendationAsBusinessIntent() {
        IntentAgentService service = new IntentAgentService(null, null, null, null, "qwen-max");

        Intent intent = ReflectionTestUtils.invokeMethod(
                service, "fallbackIntent", "暴雨天推荐几个爬山活动");

        assertEquals(Intent.ACTIVITY_RECOMMENDATION, intent);
    }

    @Test
    void fallbackShouldRouteUnknownInputToOtherInsteadOfClarifyIntent() {
        IntentAgentService service = new IntentAgentService(null, null, null, null, "qwen-max");

        Intent intent = ReflectionTestUtils.invokeMethod(
                service, "fallbackIntent", "随便聊聊别的事情");

        assertEquals(Intent.OTHER, intent);
    }
}

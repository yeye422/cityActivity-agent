package com.city.service.activity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityServiceBudgetTest {

    @Test
    void twoHundredBudgetShouldExpandToAllCheaperBuckets() {
        ActivityService service = new ActivityService(null, null, null);

        List<String> expanded = ReflectionTestUtils.invokeMethod(
                service, "expandBudgetUpperBound", List.of("200元内"));

        assertEquals(List.of("免费", "100元内", "200元内"), expanded);
    }

    @Test
    void freeBudgetShouldRemainStrictlyFree() {
        ActivityService service = new ActivityService(null, null, null);

        List<String> expanded = ReflectionTestUtils.invokeMethod(
                service, "expandBudgetUpperBound", List.of("免费"));

        assertEquals(List.of("免费"), expanded);
    }
}

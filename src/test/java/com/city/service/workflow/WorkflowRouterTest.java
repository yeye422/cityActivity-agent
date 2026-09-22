package com.city.service.workflow;

import com.city.enums.Intent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowRouterTest {
    private final WorkflowRouter router = new WorkflowRouter();

    @Test
    void shouldRouteEveryIntentToDeterministicWorkflow() {
        assertEquals(WorkflowType.RECOMMEND, router.route(Intent.ACTIVITY_RECOMMENDATION));
        assertEquals(WorkflowType.ADJUST, router.route(Intent.ACTIVITY_ADJUST));
        assertEquals(WorkflowType.PLAN, router.route(Intent.ACTIVITY_PLAN));
        assertEquals(WorkflowType.CHITCHAT, router.route(Intent.OTHER));
        assertEquals(WorkflowType.CHITCHAT, router.route(null));
    }
}

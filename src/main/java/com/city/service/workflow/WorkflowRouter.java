package com.city.service.workflow;

import com.city.enums.Intent;
import org.springframework.stereotype.Service;

/** 将语义 Intent 映射为稳定 Workflow 类型；路由保持确定性。 */
@Service
public final class WorkflowRouter {

    public WorkflowType route(Intent intent) {
        if (intent == null) return WorkflowType.CHITCHAT;
        return switch (intent) {
            case ACTIVITY_RECOMMENDATION -> WorkflowType.RECOMMEND;
            case ACTIVITY_ADJUST -> WorkflowType.ADJUST;
            case ACTIVITY_PLAN -> WorkflowType.PLAN;
            case OTHER -> WorkflowType.CHITCHAT;
        };
    }
}

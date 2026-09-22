package com.city.controller.chat;

import com.city.constants.CityConstants;
import com.city.model.ChatRequest;
import com.city.model.ChatResponse;
import com.city.model.RelaxationRequest;
import com.city.service.orchestrator.CityAgentSupervisor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 城市活动推荐对话 HTTP 入口。
 * 本层只做参数透传，完整状态机由 {@link CityAgentSupervisor#chat} 驱动。
 */
@RestController
@RequestMapping("/api/v1/city")
public class CityChatController {

    /** 多 Agent 编排服务，注入后用于处理每轮对话。 */
    private final CityAgentSupervisor supervisor;

    /** Spring 构造器注入 Orchestrator。 */
    public CityChatController(CityAgentSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    /**
     * POST /api/v1/city/chat — 同步对话接口。
     * 接收用户消息，返回澄清追问或推荐结果（含活动卡片）。
     */
    @PostMapping("/chat")
    public ChatResponse chat(
            // 从请求头 X-User-Id 读取用户 ID，缺省为 1 便于本地调试
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            // 从请求体反序列化 ChatRequest（sessionId、message、sourceMode）
            @RequestBody ChatRequest request
    ) {
        // 委托 Orchestrator 执行完整状态机，直接返回 ChatResponse
        return supervisor.chat(userId, request);
    }

    @PostMapping("/chat/relax")
    public ChatResponse showRelaxedRecommendation(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestBody RelaxationRequest request
    ) {
        return supervisor.showRelaxedRecommendation(userId, request);
    }
}

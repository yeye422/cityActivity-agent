package com.city.controller.chat;

import com.city.constants.CityConstants;
import com.city.model.ChatRequest;
import com.city.model.ChatResponse;
import com.city.model.RelaxationRequest;
import com.city.service.idempotency.ChatRequestIdempotencyService;
import com.city.service.orchestrator.CityAgentSupervisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 城市活动推荐对话 HTTP 入口。
 * 本层只负责 HTTP 边界、可选请求幂等和参数透传，完整状态机仍由 {@link CityAgentSupervisor#chat} 驱动。
 */
@RestController
@RequestMapping("/api/v1/city")
public class CityChatController {

    private final CityAgentSupervisor supervisor;
    private final ChatRequestIdempotencyService idempotencyService;

    /** 保留现有纯单测构造入口。 */
    public CityChatController(CityAgentSupervisor supervisor) {
        this(supervisor, null);
    }

    @Autowired
    public CityChatController(CityAgentSupervisor supervisor,
                              ChatRequestIdempotencyService idempotencyService) {
        this.supervisor = supervisor;
        this.idempotencyService = idempotencyService;
    }

    /**
     * POST /api/v1/city/chat — 同步对话接口。
     * Idempotency-Key 可选；缺省时保持原语义，有值时相同请求只执行一次。
     */
    @PostMapping("/chat")
    public ChatResponse chat(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ChatRequest request
    ) {
        if (idempotencyService == null) {
            return supervisor.chat(userId, request);
        }
        return idempotencyService.execute(
                userId,
                idempotencyKey,
                request,
                () -> supervisor.chat(userId, request)
        );
    }

    @PostMapping("/chat/relax")
    public ChatResponse showRelaxedRecommendation(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestBody RelaxationRequest request
    ) {
        return supervisor.showRelaxedRecommendation(userId, request);
    }
}

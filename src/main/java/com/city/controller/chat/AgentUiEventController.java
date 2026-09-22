package com.city.controller.chat;

import com.city.constants.CityConstants;
import com.city.exception.CityException;
import com.city.service.event.AgentUiEventService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/city/events")
public class AgentUiEventController {
    private final AgentUiEventService eventService;

    public AgentUiEventController(AgentUiEventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping(value = "/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @PathVariable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new CityException("sessionId 不能为空");
        return eventService.subscribe(userId, sessionId, lastEventId);
    }
}

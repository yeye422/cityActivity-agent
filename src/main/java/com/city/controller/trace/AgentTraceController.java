package com.city.controller.trace;

import com.city.constants.CityConstants;
import com.city.model.RequestTraceRow;
import com.city.model.TraceLabelRequest;
import com.city.service.trace.AgentTraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/city/debug")
public class AgentTraceController {
    private final AgentTraceService agentTraceService;

    public AgentTraceController(AgentTraceService agentTraceService) {
        this.agentTraceService = agentTraceService;
    }

    @GetMapping("/traces/{traceId}")
    public RequestTraceRow findByTraceId(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @PathVariable String traceId
    ) {
        return agentTraceService.findByTraceId(userId, traceId);
    }

    @GetMapping("/sessions/{sessionId}/traces")
    public List<RequestTraceRow> findBySessionId(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @PathVariable String sessionId,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return agentTraceService.findBySessionId(userId, sessionId, limit);
    }

    @GetMapping("/traces")
    public List<RequestTraceRow> findByTimeRange(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
            @RequestParam(value = "onlyUnlabeled", required = false) Boolean onlyUnlabeled,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return agentTraceService.findByTimeRange(userId, startAt, endAt, onlyUnlabeled, limit);
    }

    @PutMapping("/traces/{traceId}/label")
    public void updateLabel(
            @RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId,
            @PathVariable String traceId,
            @RequestBody TraceLabelRequest request
    ) {
        agentTraceService.updateLabel(userId, traceId, request);
    }
}






package com.city.service.trace;

import com.city.model.SessionState;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 请求级 Trace 生命周期边界，Supervisor 不直接依赖底层 Trace 实现。 */
@Service
public class BusinessTraceService {

    private final AgentTraceService traceService;

    public BusinessTraceService(AgentTraceService traceService) {
        this.traceService = traceService;
    }

    public String newTraceId() {
        return "trace_" + UUID.randomUUID().toString().replace("-", "");
    }

    public <T> T execute(String traceId,
                         SessionState initialState,
                         Long userId,
                         Object request,
                         RunAction<T> action) {
        try (AgentTraceService.TraceScope ignored =
                     traceService.openTrace(traceId, initialState.sessionId(), userId)) {
            long startedAt = System.nanoTime();
            traceService.recordEvent("REQUEST_RECEIVED", "HTTP", request, initialState);
            try {
                T result = action.execute();
                traceService.recordEvent(
                        "REQUEST_FINISHED", "HTTP", request, result, elapsedMs(startedAt));
                return result;
            } catch (RuntimeException error) {
                traceService.recordError("REQUEST_FAILED", "HTTP", request, error);
                throw error;
            }
        }
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    @FunctionalInterface
    public interface RunAction<T> {
        T execute();
    }
}

package com.city.service.orchestrator;

import com.city.model.ChatRequest;
import com.city.model.ChatResponse;
import com.city.model.RelaxationRequest;
import com.city.model.SessionState;
import com.city.service.session.SessionStateService;
import com.city.service.trace.BusinessTraceService;
import com.city.service.workflow.WorkflowRouter;
import com.city.service.workflow.WorkflowType;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CityFlow 请求级 Supervisor。
 *
 * <p>最终职责严格限定为：加载会话状态、创建 Run/Trace、路由 Workflow、委派执行。
 * 具体理解、Guard、时间处理、推荐/规划、Relaxation 与状态提交均下沉到 AgentRunService / Workflow。</p>
 */
@Service
public class CityAgentSupervisor {

    private final SessionStateService sessionStateService;
    private final WorkflowRouter workflowRouter;
    private final AgentRunService agentRunService;
    private final BusinessTraceService businessTraceService;
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public CityAgentSupervisor(SessionStateService sessionStateService,
                               WorkflowRouter workflowRouter,
                               AgentRunService agentRunService,
                               BusinessTraceService businessTraceService) {
        this.sessionStateService = sessionStateService;
        this.workflowRouter = workflowRouter;
        this.agentRunService = agentRunService;
        this.businessTraceService = businessTraceService;
    }

    public ChatResponse chat(Long userId, ChatRequest request) {
        agentRunService.validateChatRequest(request);
        SessionState initialState = sessionStateService.loadOrCreate(
                request.sessionId(), userId, request.sourceMode());
        String traceId = businessTraceService.newTraceId();

        return businessTraceService.execute(
                traceId,
                initialState,
                userId,
                request,
                () -> {
                    Object lock = sessionLocks.computeIfAbsent(initialState.sessionId(), key -> new Object());
                    synchronized (lock) {
                        AgentRunService.PreparedRun run =
                                agentRunService.prepare(userId, request, traceId, initialState);
                        if (run.terminalResponse() != null) {
                            return run.terminalResponse();
                        }
                        WorkflowType workflow = workflowRouter.route(run.intent().intent());
                        return agentRunService.execute(request.message(), traceId, run, workflow);
                    }
                });
    }

    public ChatResponse showRelaxedRecommendation(Long userId, RelaxationRequest request) {
        agentRunService.validateRelaxationRequest(request);
        SessionState loadedState = sessionStateService.loadExisting(request.sessionId(), userId);
        String traceId = businessTraceService.newTraceId();

        return businessTraceService.execute(
                traceId,
                loadedState,
                userId,
                request,
                () -> {
                    Object lock = sessionLocks.computeIfAbsent(loadedState.sessionId(), key -> new Object());
                    synchronized (lock) {
                        return agentRunService.executeRelaxation(userId, request, traceId, loadedState);
                    }
                });
    }
}

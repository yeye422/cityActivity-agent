package com.city.service.orchestrator;

import com.city.enums.ClarifyField;
import com.city.enums.Intent;
import com.city.enums.SessionPhase;
import com.city.enums.SourceMode;
import com.city.model.ChatResponse;
import com.city.model.DecisionResponseResult;
import com.city.model.ResponseResult;
import com.city.model.RiskGuardResult;
import com.city.model.SessionState;
import com.city.model.TimeConstraint;
import com.city.service.risk.RiskGuardService;
import com.city.service.session.SessionService;
import com.city.service.session.SessionStateService;
import com.city.service.trace.AgentTraceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Supervisor 唯一状态提交边界。
 *
 * <p>Worker/Agent 只返回结构化结果；本服务统一完成输出安全校验、推荐历史更新、
 * SessionState 持久化、assistant message 落库与 ChatResponse 构造。</p>
 */
@Service
public class DecisionCommitService {
    private final SessionStateService sessionStateService;
    private final SessionService sessionService;
    private final RiskGuardService riskGuardService;
    private final AgentTraceService traceService;

    public DecisionCommitService(SessionStateService sessionStateService,
                                 SessionService sessionService,
                                 RiskGuardService riskGuardService,
                                 AgentTraceService traceService) {
        this.sessionStateService = sessionStateService;
        this.sessionService = sessionService;
        this.riskGuardService = riskGuardService;
        this.traceService = traceService;
    }

    public ChatResponse commitDecision(String userInput,
                                       String traceId,
                                       SessionState state,
                                       DecisionResponseResult result,
                                       boolean publicFallbackUsed) {
        Intent intent = state.currentIntent() == null ? Intent.ACTIVITY_RECOMMENDATION : state.currentIntent();
        ResponseResult response = result.response();
        if (publicFallbackUsed) {
            response = prependPublicFallbackNotice(response);
        }
        response = applyOutputRiskGuard(userInput, intent, response);

        List<Long> lastIds = result.recommend().recommendations().stream()
                .map(option -> option.itemId())
                .toList();
        String queryKey = recommendationQueryKey(state);
        SessionState savedState = queryKey.equals(state.recommendationQueryKey())
                ? state.appendLastRecommendations(lastIds)
                : state.withLastRecommendations(lastIds);
        savedState = savedState.withRecommendationQueryKey(queryKey)
                .withPendingClarifyField(null)
                .withPendingRelaxationContext(null);

        sessionStateService.save(savedState);
        sessionService.appendMessage(savedState.sessionId(), "assistant", response.speechText(), intent.name(), traceId);
        ChatResponse chatResponse = withConversationContext(
                ChatResponse.answer(savedState.sessionId(), traceId, response.speechText(),
                        response.displayBlocks(), response.nextAction()),
                savedState);
        traceService.recordEvent("STATE_COMMITTED", "SESSION", state, savedState);
        traceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);
        return chatResponse;
    }

    public ChatResponse commitClarification(String traceId,
                                            SessionState state,
                                            ClarifyField field,
                                            String question) {
        SessionState savedState = state.withPhase(SessionPhase.CLARIFY)
                .withPendingClarifyField(field)
                .withPendingRelaxationContext(null);
        sessionStateService.save(savedState);
        String businessIntent = savedState.currentIntent() == null ? null : savedState.currentIntent().name();
        sessionService.appendMessage(savedState.sessionId(), "assistant", question, businessIntent, traceId);
        ChatResponse response = withConversationContext(
                ChatResponse.clarify(savedState.sessionId(), traceId, question, List.of(field.key())),
                savedState);
        traceService.recordEvent("STATE_COMMITTED", "SESSION", state, savedState);
        traceService.recordEvent("RESPONSE_READY", "CLARIFY", savedState, response);
        return response;
    }

    public ChatResponse commitText(String traceId,
                                   SessionState state,
                                   Intent responseIntent,
                                   ResponseResult response,
                                   boolean preserveBusinessState) {
        SessionState savedState = preserveBusinessState
                ? state
                : state.withIntent(responseIntent)
                        .withPendingClarifyField(null)
                        .withPendingRelaxationContext(null);
        sessionStateService.save(savedState);
        String messageIntent = responseIntent == null ? null : responseIntent.name();
        sessionService.appendMessage(savedState.sessionId(), "assistant", response.speechText(), messageIntent, traceId);
        ChatResponse chatResponse = withConversationContext(
                ChatResponse.answer(savedState.sessionId(), traceId, response.speechText(),
                        response.displayBlocks(), response.nextAction()),
                savedState);
        traceService.recordEvent("STATE_COMMITTED", "SESSION", state, savedState);
        traceService.recordEvent("RESPONSE_READY", "RESPONSE", savedState, chatResponse);
        return chatResponse;
    }

    public static String recommendationQueryKey(SessionState state) {
        if (state == null) return "";
        List<String> unconstrained = state.unconstrainedSlots() == null
                ? List.of()
                : state.unconstrainedSlots().stream().sorted().toList();
        TimeConstraint time = state.timeConstraint();
        String timeKey = time == null
                ? "null|null|null|null"
                : String.valueOf(time.dateStart()) + "|" + time.dateEnd()
                + "|" + time.startTime() + "|" + time.endTime();
        return String.valueOf(state.sourceMode()) + "|" + state.slots() + "|" + state.excludedSlots()
                + "|" + unconstrained + "|" + timeKey;
    }

    private ResponseResult applyOutputRiskGuard(String userInput, Intent intent, ResponseResult response) {
        RiskGuardResult guard = riskGuardService.check(userInput, response);
        traceService.recordEvent("RISK_GUARD_OUTPUT_CHECKED", "GUARD",
                Map.of("intent", intent == null ? "" : intent.name()), guard);
        if (!guard.passed()) {
            ResponseResult rewritten = ResponseResult.textOnly(guard.rewriteSuggestion());
            traceService.recordEvent("RISK_GUARD_OUTPUT_REWRITTEN", "GUARD", guard, rewritten);
            return rewritten;
        }
        return response;
    }

    private ResponseResult prependPublicFallbackNotice(ResponseResult response) {
        String notice = "你的个人活动库暂时没有匹配项，我先从公共活动中帮你挑了几个。";
        return new ResponseResult(notice + "\n" + response.speechText(),
                response.displayBlocks(), response.nextAction());
    }

    private ChatResponse withConversationContext(ChatResponse response, SessionState state) {
        response.appliedSlots(state.slots());
        response.excludedSlots(state.excludedSlots());
        response.timeConstraint(state.timeConstraint());
        return response;
    }
}

package com.city.service.clarify;

import com.city.agent.factory.AgentFactory;
import com.city.model.ClarifyResult;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.service.trace.AgentTraceService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * ClarifyAgent 调用服务。
 * 规则层（ClarifyRuleService）先判 ASK/READY；仅 ASK 时才调 LLM 生成自然语言追问。
 */
@Service
public class ClarifyAgentService {

    private final AgentFactory agentFactory;
    private final ClarifyRuleService clarifyRuleService;
    private final AgentTraceService agentTraceService;
    private final String modelName;

    public ClarifyAgentService(
            AgentFactory agentFactory,
            ClarifyRuleService clarifyRuleService,
            AgentTraceService agentTraceService,
            @Value("${diet.llm.light-model:qwen-turbo}") String modelName
    ) {
        this.agentFactory = agentFactory;
        this.clarifyRuleService = clarifyRuleService;
        this.agentTraceService = agentTraceService;
        this.modelName = modelName;
    }

    public ClarifyResult decide(String sessionId,
                                String userInput,
                                SlotBundle slots,
                                TimeConstraint timeConstraint,
                                Set<String> unconstrainedSlots) {
        List<String> missingSlots = clarifyRuleService.missingSlots(slots, timeConstraint, unconstrainedSlots);
        if (missingSlots.isEmpty()) {
            return ClarifyResult.ready();
        }
        if (timeConstraint != null && timeConstraint.hasDate()) {
            return ClarifyResult.ask(clarifyRuleService.fallbackQuestion(missingSlots, timeConstraint), missingSlots);
        }
        try {
            ReActAgent agent = agentFactory.get(sessionId).clarify();
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "ClarifyAgent",
                    modelName,
                    agent,
                    buildUserPrompt(userInput, slots, missingSlots)
            );
            String question = response.getTextContent() == null ? "" : response.getTextContent().trim();
            if (question.isBlank()) {
                question = clarifyRuleService.fallbackQuestion(missingSlots, timeConstraint);
            }
            return ClarifyResult.ask(question, missingSlots);
        } catch (Exception ignored) {
            return ClarifyResult.ask(clarifyRuleService.fallbackQuestion(missingSlots, timeConstraint), missingSlots);
        }
    }

    public ClarifyResult decide(String sessionId, String userInput, SlotBundle slots, TimeConstraint timeConstraint) {
        return decide(sessionId, userInput, slots, timeConstraint, Set.of());
    }

    public ClarifyResult decide(String sessionId, String userInput, SlotBundle slots) {
        return decide(sessionId, userInput, slots, TimeConstraint.empty(), Set.of());
    }

    private String buildUserPrompt(String userInput, SlotBundle slots, List<String> missingSlots) {
        return """
                用户原话：%s
                已知信息：%s
                缺失字段：%s
                """.formatted(userInput, slots, missingSlots);
    }
}

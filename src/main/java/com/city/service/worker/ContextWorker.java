package com.city.service.worker;

import com.city.model.ConversationTurn;
import com.city.model.IntentResult;
import com.city.model.SessionState;
import com.city.service.intent.IntentAgentService;
import com.city.service.intent.IntentReviseService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 只负责把会话输入转换为结构化语义 Patch，不写 SessionState。 */
@Service
public final class ContextWorker {
    private final IntentAgentService intentAgentService;
    private final IntentReviseService intentReviseService;

    public ContextWorker(IntentAgentService intentAgentService, IntentReviseService intentReviseService) {
        this.intentAgentService = intentAgentService;
        this.intentReviseService = intentReviseService;
    }

    public Result understand(String sessionId,
                             Long userId,
                             String userInput,
                             SessionState state,
                             List<ConversationTurn> history) {
        IntentResult raw = intentAgentService.recognize(
                sessionId, userId, userInput, state.slots(), state.timeConstraint(), history);
        return new Result(raw, intentReviseService.revise(state, raw, userInput));
    }

    public record Result(IntentResult raw, IntentResult revised) {
        public Result {
            if (raw == null || revised == null) throw new IllegalArgumentException("ContextWorker 结果不能为空");
        }
    }
}

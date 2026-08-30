package com.city.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@NoArgsConstructor
public class ChatResponse {
    private String sessionId;
    private String traceId;
    private String responseType;
    private String speechText;
    private List<ActivityResponse> displayBlocks;
    private String nextAction;
    private String clarifyQuestion;
    private List<String> missingSlots;
    private List<RelaxationOption> relaxationOptions;
    /** 返回给前端的当前有效筛选状态，便于用户核对系统理解。 */
    private SlotBundle appliedSlots;
    private SlotBundle excludedSlots;
    private TimeConstraint timeConstraint;

    public ChatResponse(String sessionId, String traceId, String responseType, String speechText,
                        List<ActivityResponse> displayBlocks, String nextAction, String clarifyQuestion,
                        List<String> missingSlots, List<RelaxationOption> relaxationOptions) {
        this.sessionId = sessionId;
        this.traceId = traceId;
        this.responseType = responseType;
        this.speechText = speechText;
        this.displayBlocks = displayBlocks;
        this.nextAction = nextAction;
        this.clarifyQuestion = clarifyQuestion;
        this.missingSlots = missingSlots;
        this.relaxationOptions = relaxationOptions;
    }

    public static ChatResponse answer(String sessionId, String speechText, List<ActivityResponse> displayBlocks, String nextAction) {
        return answer(sessionId, null, speechText, displayBlocks, nextAction);
    }

    public static ChatResponse answer(String sessionId, String traceId, String speechText, List<ActivityResponse> displayBlocks, String nextAction) {
        return new ChatResponse(sessionId, traceId, "ANSWER", speechText, displayBlocks == null ? List.of() : displayBlocks, nextAction, null, List.of(), List.of());
    }

    public static ChatResponse clarify(String sessionId, String question, List<String> missingSlots) {
        return clarify(sessionId, null, question, missingSlots);
    }

    public static ChatResponse clarify(String sessionId, String traceId, String question, List<String> missingSlots) {
        return new ChatResponse(sessionId, traceId, "CLARIFY", question, List.of(), "ASK_CLARIFY", question, missingSlots == null ? List.of() : List.copyOf(missingSlots), List.of());
    }

    public static ChatResponse relaxation(String sessionId, String traceId, String speechText, List<RelaxationOption> options) {
        return new ChatResponse(sessionId, traceId, "RELAX_OPTIONS", speechText, List.of(), "CHOOSE_RELAXATION", null, List.of(), options == null ? List.of() : List.copyOf(options));
    }
}


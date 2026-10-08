package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityResponse;
import com.city.model.ActivitySessionResponse;
import com.city.model.DecisionResponseResult;
import com.city.model.PlanCandidate;
import com.city.model.RecommendResult;
import com.city.model.RecommendedActivityOption;
import com.city.model.ResponseResult;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.agent.PlanningDecision;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/** PlanningAgent 的确定性响应生成层，不再依赖旧 PlanResponseAgent。 */
@Service
public class PlanningResponseGeneratorService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public DecisionResponseResult generate(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle slots,
            PlanningAgentExecutionResult execution,
            WeatherRecommendationContext weather
    ) {
        Objects.requireNonNull(execution, "execution");
        PlanCandidate acceptedPlan = Objects.requireNonNull(execution.acceptedPlan(), "acceptedPlan");
        PlanningDecision decision = Objects.requireNonNull(execution.decision(), "decision");
        if (acceptedPlan.items().isEmpty()) {
            return new DecisionResponseResult(
                    RecommendResult.empty(),
                    ResponseResult.textOnly("当前条件下还没有形成可执行的活动组合，可以放宽一个时段或活动偏好后再试。")
            );
        }

        List<RecommendedActivityOption> options = acceptedPlan.items().stream()
                .map(item -> new RecommendedActivityOption(
                        item.activity().id(),
                        item.activity().sourceType(),
                        item.activity().name(),
                        "安排在" + formatRange(item) + "，并已通过当前规划硬约束校验。",
                        item.activity().matchScore(),
                        item.activity().slots()
                ))
                .toList();
        RecommendResult recommend = new RecommendResult(options, false);
        List<ActivityResponse> displayBlocks = acceptedPlan.items().stream()
                .map(PlanCandidate.Item::activity)
                .map(ActivityResponse::from)
                .toList();
        ResponseResult response = new ResponseResult(
                buildSpeech(decision, acceptedPlan, weather),
                displayBlocks,
                "WAIT_USER"
        );
        return new DecisionResponseResult(recommend, response);
    }

    private String buildSpeech(PlanningDecision decision,
                               PlanCandidate acceptedPlan,
                               WeatherRecommendationContext weather) {
        StringBuilder builder = new StringBuilder();
        String summary = decision.decisionSummary() == null ? "" : decision.decisionSummary().trim();
        builder.append(summary.isBlank() ? "按当前条件，我整理了一版可执行安排：" : summary);
        for (int i = 0; i < acceptedPlan.items().size(); i++) {
            PlanCandidate.Item item = acceptedPlan.items().get(i);
            builder.append("\n").append(i + 1).append(". ")
                    .append(formatRange(item)).append("：")
                    .append(item.activity().name());
            appendSessionFact(builder, item.session());
        }
        if (weather != null && weather.active() && weather.summary() != null && !weather.summary().isBlank()) {
            builder.append("\n天气参考：").append(weather.summary().trim());
        }
        return builder.toString();
    }


    private String formatRange(PlanCandidate.Item item) {
        if (item == null || item.startAt() == null || item.endAt() == null) return "时间待定";
        return DATE_TIME.format(item.startAt()) + "-" + item.endAt().toLocalTime();
    }

    private void appendSessionFact(StringBuilder builder, ActivitySessionResponse session) {
        if (session == null) return;
        if (session.venueName() != null && !session.venueName().isBlank()) {
            builder.append("，地点 ").append(session.venueName().trim());
        }
    }
}

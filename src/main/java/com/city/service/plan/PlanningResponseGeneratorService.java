package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityResponse;
import com.city.model.ActivitySessionResponse;
import com.city.model.PlanCandidate;
import com.city.model.RecommendResult;
import com.city.model.RecommendedActivityOption;
import com.city.model.ResponseResult;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.PlanningAgentExecutionResult;
import com.city.model.agent.PlanningDecision;
import com.city.service.recommend.RecommendResponseAgentService;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * PlanningAgent 的确定性响应生成层。
 *
 * <p>PlanningAgent 已完成方案取舍，PlanningSolver 已完成最终硬约束复核，因此这里不再调用
 * PlanResponseAgent。服务只把 acceptedPlan 中的权威 Activity / Session 转成前端结构和稳定文本。
 * 旧 PlanResponseAgent 仅保留给 legacy fallback。</p>
 */
@Service
public class PlanningResponseGeneratorService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public RecommendResponseAgentService.Result generate(
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
            return new RecommendResponseAgentService.Result(
                    RecommendResult.empty(),
                    ResponseResult.textOnly("当前条件下还没有形成可执行的活动组合，可以放宽一个时段或活动偏好后再试。")
            );
        }

        List<RecommendedActivityOption> options = acceptedPlan.items().stream()
                .map(item -> new RecommendedActivityOption(
                        item.activity().id(),
                        item.activity().sourceType(),
                        item.activity().name(),
                        "安排在" + item.period() + "，并已通过当前规划硬约束校验。",
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
        return new RecommendResponseAgentService.Result(recommend, response);
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
                    .append(item.period()).append("：")
                    .append(item.activity().name());
            appendSessionFact(builder, item.session());
        }
        if (weather != null && weather.active() && weather.summary() != null && !weather.summary().isBlank()) {
            builder.append("\n天气参考：").append(weather.summary().trim());
        }
        return builder.toString();
    }

    private void appendSessionFact(StringBuilder builder, ActivitySessionResponse session) {
        if (session == null) return;
        if (session.startAt() != null) {
            builder.append("，场次 ").append(DATE_TIME.format(session.startAt()));
            if (session.endAt() != null) {
                builder.append("-").append(session.endAt().toLocalTime());
            }
        }
        if (session.venueName() != null && !session.venueName().isBlank()) {
            builder.append("，地点 ").append(session.venueName().trim());
        }
    }
}

package com.city.service.recommend;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityResponse;
import com.city.model.DecisionResponseResult;
import com.city.model.RecommendResult;
import com.city.model.RecommendedActivityOption;
import com.city.model.ResponseResult;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.RecommendationDecision;
import com.city.model.agent.RecommendationExecutionResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** RecommendationAgent 的确定性响应生成层，不再依赖旧 ResponseAgent。 */
@Service
public class RecommendationResponseGeneratorService {

    public DecisionResponseResult generate(
            String sessionId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle slots,
            RecommendationExecutionResult execution,
            WeatherRecommendationContext weather
    ) {
        Objects.requireNonNull(execution, "execution");
        RecommendationDecision decision = Objects.requireNonNull(execution.decision(), "decision");
        List<ActivityItem> selected = execution.selectedActivities() == null
                ? List.of()
                : List.copyOf(execution.selectedActivities());
        if (selected.isEmpty()) {
            return new DecisionResponseResult(
                    RecommendResult.empty(),
                    ResponseResult.textOnly("暂时没有找到足够匹配的活动，可以补充时间、活动类型或体验偏好。")
            );
        }

        Map<Long, String> reasons = assessmentReasons(decision);
        List<RecommendedActivityOption> options = selected.stream()
                .map(activity -> new RecommendedActivityOption(
                        activity.id(),
                        activity.sourceType(),
                        activity.name(),
                        reasons.getOrDefault(activity.id(), fallbackReason(activity, decision)),
                        activity.matchScore(),
                        activity.slots()
                ))
                .toList();
        RecommendResult recommend = new RecommendResult(options, false);
        List<ActivityResponse> displayBlocks = selected.stream()
                .map(ActivityResponse::from)
                .toList();
        ResponseResult response = new ResponseResult(
                buildSpeech(decision, options, weather),
                displayBlocks,
                "WAIT_USER"
        );
        return new DecisionResponseResult(recommend, response);
    }

    private Map<Long, String> assessmentReasons(RecommendationDecision decision) {
        Map<Long, String> reasons = new LinkedHashMap<>();
        if (decision.assessments() == null) return reasons;
        for (RecommendationDecision.CandidateAssessment assessment : decision.assessments()) {
            if (assessment == null || assessment.activityId() == null) continue;
            String reason = assessment.reason() == null ? "" : assessment.reason().trim();
            if (!reason.isBlank()) {
                reasons.putIfAbsent(assessment.activityId(), reason);
            }
        }
        return reasons;
    }

    private String fallbackReason(ActivityItem activity, RecommendationDecision decision) {
        String summary = decision.decisionSummary() == null ? "" : decision.decisionSummary().trim();
        if (!summary.isBlank()) {
            return activity.name() + "与本轮优先目标较匹配：" + summary;
        }
        return activity.name() + "与本轮需求匹配度较高。";
    }

    private String buildSpeech(RecommendationDecision decision,
                               List<RecommendedActivityOption> options,
                               WeatherRecommendationContext weather) {
        StringBuilder builder = new StringBuilder();
        String summary = decision.decisionSummary() == null ? "" : decision.decisionSummary().trim();
        builder.append(summary.isBlank() ? "结合你这轮的需求，我更推荐这些活动：" : summary);
        for (int i = 0; i < options.size(); i++) {
            RecommendedActivityOption option = options.get(i);
            builder.append("\n").append(i + 1).append(". ")
                    .append(option.name()).append("：").append(option.reason());
        }
        if (weather != null && weather.active() && weather.summary() != null && !weather.summary().isBlank()) {
            builder.append("\n天气参考：").append(weather.summary().trim());
        }
        return builder.toString();
    }
}

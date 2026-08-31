package com.city.service.activity;

import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivityRankResult;
import com.city.model.ActivityRankScore;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 活动重排服务（Orchestrator 推荐流水线第二层）。
 * Search 已负责九维正负约束等硬条件过滤；这里不再重复计算槽位匹配分，
 * 只处理历史推荐 ID 排除、时间窗口适配和天气上下文调整。
 */
@Service
public class ActivityRankService {
    private static final int MAX_RANKED_CANDIDATES = 10;

    public ActivityRankResult rank(ActivityRankRequest request) {
        return rank(request, WeatherRecommendationContext.inactive());
    }

    /** 天气只影响排序，不会把活动从结果集中剔除。 */
    public ActivityRankResult rank(ActivityRankRequest request, WeatherRecommendationContext weather) {
        if (request == null || request.candidates() == null || request.candidates().isEmpty()) {
            return new ActivityRankResult(List.of(), List.of());
        }
        Set<Long> excludeIds = new HashSet<>(
                request.excludeActivityIds() == null ? List.of() : request.excludeActivityIds());

        List<ScoredActivity> scored = request.candidates().stream()
                .filter(item -> item != null && !excludeIds.contains(item.id()))
                .map(item -> score(item, request.timeConstraint(), weather))
                .sorted(Comparator
                        .comparingDouble((ScoredActivity item) -> item.score().finalScore()).reversed()
                        .thenComparing(item -> item.activity().id(), Comparator.nullsLast(Long::compareTo)))
                .limit(MAX_RANKED_CANDIDATES)
                .toList();

        return new ActivityRankResult(
                scored.stream().map(ScoredActivity::activity).toList(),
                scored.stream().map(ScoredActivity::score).toList());
    }

    private ScoredActivity score(ActivityItem item,
                                 TimeConstraint timeConstraint,
                                 WeatherRecommendationContext weather) {
        Double timeScore = timeScore(item, timeConstraint);
        // Search 已保证候选满足九维硬约束；没有具体时段时所有候选使用相同中性基准分。
        double baseScore = timeScore == null ? 1.0 : clamp(timeScore);
        double weatherAdjusted = weatherScore(baseScore, item.slots(), weather);
        double weatherAdjustment = weatherAdjusted - baseScore;

        ActivityItem rankedItem = new ActivityItem(
                item.id(), item.sourceType(), item.ownerUserId(), item.name(), item.slots(),
                item.validFrom(), item.validTo(), item.validStartTime(), item.validEndTime(),
                item.durationMinutes(), weatherAdjusted);
        WeatherRecommendationContext.Status weatherStatus = weather == null || weather.status() == null
                ? WeatherRecommendationContext.Status.NOT_REQUESTED
                : weather.status();
        ActivityRankScore breakdown = new ActivityRankScore(
                item.id(), timeScore, weatherAdjustment, weatherAdjusted, weatherStatus);
        return new ScoredActivity(rankedItem, breakdown);
    }

    /**
     * 用户指定时段时，计算活动有效/可参加时间窗口与用户时间窗的覆盖程度。
     * validStartTime~validEndTime 表示可安排时间或具体场次窗口，不等同于活动实际耗时；
     * 实际/预计耗时由 durationMinutes 单独提供给 Plan 层。
     */
    private Double timeScore(ActivityItem item, TimeConstraint timeConstraint) {
        if (timeConstraint == null || !timeConstraint.hasTime()) return null;
        if (item.validStartTime() == null || item.validEndTime() == null) return 0.0;
        return overlapRatio(
                item.validStartTime(), item.validEndTime(),
                timeConstraint.startTime(), timeConstraint.endTime());
    }

    private double overlapRatio(LocalTime activityStart, LocalTime activityEnd,
                                LocalTime queryStart, LocalTime queryEnd) {
        if (activityStart == null || activityEnd == null || queryStart == null || queryEnd == null) return 0.0;
        int activityStartMinute = minuteOfDay(activityStart);
        int activityEndMinute = minuteOfDay(activityEnd);
        if (activityEndMinute <= activityStartMinute) activityEndMinute += 24 * 60;
        int windowDuration = activityEndMinute - activityStartMinute;
        if (windowDuration <= 0) return 0.0;

        int queryStartMinute = minuteOfDay(queryStart);
        int queryEndMinute = minuteOfDay(queryEnd);
        if (queryEndMinute <= queryStartMinute) queryEndMinute += 24 * 60;

        int bestOverlap = 0;
        for (int shift : new int[]{-24 * 60, 0, 24 * 60}) {
            int shiftedStart = activityStartMinute + shift;
            int shiftedEnd = activityEndMinute + shift;
            int overlap = Math.max(0,
                    Math.min(shiftedEnd, queryEndMinute) - Math.max(shiftedStart, queryStartMinute));
            bestOverlap = Math.max(bestOverlap, overlap);
        }
        return clamp(bestOverlap * 1.0 / windowDuration);
    }

    private int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private double weatherScore(double baseScore, SlotBundle item, WeatherRecommendationContext weather) {
        if (weather == null || !weather.active() || item == null) return baseScore;
        Set<String> features = Set.copyOf(item.feature() == null ? List.of() : item.feature());
        if (features.contains("室内")) {
            return clamp(baseScore * 0.88 + 0.12);
        }
        if (features.contains("户外") || features.contains("室外")) {
            return clamp(baseScore * 0.82);
        }
        return baseScore;
    }

    private double clamp(double score) {
        return Math.max(0, Math.min(1, score));
    }

    private record ScoredActivity(ActivityItem activity, ActivityRankScore score) {}
}

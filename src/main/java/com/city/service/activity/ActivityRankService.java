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
 * 活动相关性重排服务（Orchestrator 推荐流水线第二层）。
 * Search 负责硬约束召回；这里融合九维槽位、时间窗口适配和天气上下文。
 */
@Service
public class ActivityRankService {
    private static final int MAX_RANKED_CANDIDATES = 10;
    private static final double SLOT_WEIGHT_WITH_TIME = 0.80;
    private static final double TIME_WEIGHT = 0.20;
    private static final List<String> BUDGET_ORDER = List.of("免费", "100元内", "200元内", "300元内");

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
                .map(item -> score(item, request.slots(), request.timeConstraint(), weather))
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
                                 SlotBundle query,
                                 TimeConstraint timeConstraint,
                                 WeatherRecommendationContext weather) {
        double slotScore = slotScore(item.slots(), query);
        Double timeScore = timeScore(item, timeConstraint);
        double relevance = combineRelevance(slotScore, timeScore, hasActiveSlot(query));
        double weatherAdjusted = weatherScore(relevance, item.slots(), weather);
        double weatherAdjustment = weatherAdjusted - relevance;

        ActivityItem rankedItem = new ActivityItem(
                item.id(), item.sourceType(), item.ownerUserId(), item.name(), item.slots(),
                item.validFrom(), item.validTo(), item.validStartTime(), item.validEndTime(),
                item.durationMinutes(), weatherAdjusted);
        WeatherRecommendationContext.Status weatherStatus = weather == null || weather.status() == null
                ? WeatherRecommendationContext.Status.NOT_REQUESTED
                : weather.status();
        ActivityRankScore breakdown = new ActivityRankScore(
                item.id(), slotScore, timeScore, weatherAdjustment, weatherAdjusted, weatherStatus);
        return new ScoredActivity(rankedItem, breakdown);
    }

    private double combineRelevance(double slotScore, Double timeScore, boolean hasActiveSlot) {
        if (timeScore == null) return slotScore;
        if (!hasActiveSlot) return clamp(timeScore);
        return clamp(slotScore * SLOT_WEIGHT_WITH_TIME + timeScore * TIME_WEIGHT);
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

    private double weatherScore(double relevanceScore, SlotBundle item, WeatherRecommendationContext weather) {
        if (weather == null || !weather.active() || item == null) return relevanceScore;
        Set<String> features = Set.copyOf(item.feature() == null ? List.of() : item.feature());
        if (features.contains("室内")) {
            return clamp(relevanceScore * 0.88 + 0.12);
        }
        if (features.contains("户外") || features.contains("室外")) {
            return clamp(relevanceScore * 0.82);
        }
        return relevanceScore;
    }

    /** 计算活动九维 slots 与查询 slots 的有效维度平均重叠比例。 */
    private double slotScore(SlotBundle item, SlotBundle query) {
        SlotBundle safeItem = item == null ? SlotBundle.empty() : item;
        SlotBundle safeQuery = query == null ? SlotBundle.empty() : query;
        double total = 0.0;
        int dimensions = 0;
        ScorePart[] parts = {
                scorePart(safeItem.city(), safeQuery.city()),
                scorePart(safeItem.location(), safeQuery.location()),
                scorePart(safeItem.experienceGoal(), safeQuery.experienceGoal()),
                scorePart(safeItem.companion(), safeQuery.companion()),
                budgetScorePart(safeItem.budget(), safeQuery.budget()),
                scorePart(safeItem.activityType(), safeQuery.activityType()),
                scorePart(safeItem.style(), safeQuery.style()),
                scorePart(safeItem.duration(), safeQuery.duration()),
                scorePart(safeItem.feature(), safeQuery.feature())
        };
        for (ScorePart part : parts) {
            if (part.active()) {
                total += part.score();
                dimensions++;
            }
        }
        return dimensions == 0 ? 0 : clamp(total / dimensions);
    }

    private boolean hasActiveSlot(SlotBundle query) {
        return query != null && !query.isEmpty();
    }

    private ScorePart scorePart(List<String> itemValues, List<String> queryValues) {
        return new ScorePart(queryValues != null && !queryValues.isEmpty(), overlap(itemValues, queryValues));
    }

    private ScorePart budgetScorePart(List<String> itemBudgets, List<String> queryBudgets) {
        if (queryBudgets == null || queryBudgets.isEmpty()) return new ScorePart(false, 0.0);
        int queryMax = maxBudgetIndex(queryBudgets);
        if (queryMax < 0) return new ScorePart(true, overlap(itemBudgets, queryBudgets));
        if (itemBudgets == null || itemBudgets.isEmpty()) return new ScorePart(true, 0.0);
        boolean withinLimit = itemBudgets.stream()
                .mapToInt(BUDGET_ORDER::indexOf)
                .anyMatch(index -> index >= 0 && index <= queryMax);
        return new ScorePart(true, withinLimit ? 1.0 : 0.0);
    }

    private int maxBudgetIndex(List<String> budgets) {
        int max = -1;
        for (String budget : budgets) max = Math.max(max, BUDGET_ORDER.indexOf(budget));
        return max;
    }

    private double overlap(List<String> itemValues, List<String> queryValues) {
        if (queryValues == null || queryValues.isEmpty()) return 0;
        Set<String> itemSet = Set.copyOf(itemValues == null ? List.of() : itemValues);
        long hits = queryValues.stream().filter(itemSet::contains).count();
        return hits * 1.0 / queryValues.size();
    }

    private double clamp(double score) {
        return Math.max(0, Math.min(1, score));
    }

    private record ScorePart(boolean active, double score) {}
    private record ScoredActivity(ActivityItem activity, ActivityRankScore score) {}
}

package com.city.service.activity;

import com.city.enums.PreferencePolarity;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivityRankResult;
import com.city.model.ActivityRankScore;
import com.city.model.PreferenceFact;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.service.memory.PreferenceMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务重排层。
 *
 * <p>词面与语义相关性已在 Pinecone 双通道完成并经 RRF 合并；
 * 本层只消费 hybrid retrieval prior，再叠加时间、天气和长期偏好。</p>
 */
@Service
public class ActivityRankService {
    private static final Logger log = LoggerFactory.getLogger(ActivityRankService.class);
    private static final int MAX_RANKED_CANDIDATES = 10;
    private static final double RETRIEVAL_WEIGHT = 0.12;

    private final PreferenceMemoryService preferenceMemoryService;

    public ActivityRankService() {
        this.preferenceMemoryService = null;
    }

    @Autowired
    public ActivityRankService(PreferenceMemoryService preferenceMemoryService) {
        this.preferenceMemoryService = preferenceMemoryService;
    }

    public ActivityRankResult rank(ActivityRankRequest request) {
        return rank(request, WeatherRecommendationContext.inactive());
    }

    public ActivityRankResult rank(ActivityRankRequest request, WeatherRecommendationContext weather) {
        return rankInternal(request, weather, Map.of());
    }

    public ActivityRankResult rankWithRetrievalPrior(ActivityRankRequest request,
                                                     WeatherRecommendationContext weather,
                                                     Map<Long, Double> hybridScores) {
        return rankInternal(request, weather, hybridScores == null ? Map.of() : hybridScores);
    }

    private ActivityRankResult rankInternal(ActivityRankRequest request,
                                            WeatherRecommendationContext weather,
                                            Map<Long, Double> hybridScores) {
        if (request == null || request.candidates() == null || request.candidates().isEmpty()) {
            return new ActivityRankResult(List.of(), List.of());
        }

        Set<Long> excludeIds = new HashSet<>(
                request.excludeActivityIds() == null ? List.of() : request.excludeActivityIds());
        List<PreferenceFact> preferences = loadPreferences(request.userId());

        List<ScoredActivity> scored = request.candidates().stream()
                .filter(item -> item != null && !excludeIds.contains(item.id()))
                .map(item -> score(
                        item,
                        request.slots(),
                        request.timeConstraint(),
                        weather,
                        hybridScores.getOrDefault(item.id(), 0.0),
                        preferences))
                .sorted(Comparator
                        .comparingDouble(ScoredActivity::sortScore).reversed()
                        .thenComparing(item -> item.activity().id(), Comparator.nullsLast(Long::compareTo)))
                .limit(MAX_RANKED_CANDIDATES)
                .toList();

        return new ActivityRankResult(
                scored.stream().map(ScoredActivity::activity).toList(),
                scored.stream().map(ScoredActivity::score).toList());
    }

    private ScoredActivity score(ActivityItem item,
                                 SlotBundle explicitSlots,
                                 TimeConstraint timeConstraint,
                                 WeatherRecommendationContext weather,
                                 double hybridScore,
                                 List<PreferenceFact> preferences) {
        Double timeScore = timeScore(item, timeConstraint);
        double baseScore = timeScore == null ? 1.0 : clamp(timeScore);
        double weatherAdjusted = weatherScore(baseScore, item.slots(), weather);
        double weatherAdjustment = weatherAdjusted - baseScore;
        double lexicalAdjustment = 0.0;
        double hybridRetrievalAdjustment = hybridScore * RETRIEVAL_WEIGHT;
        double preferenceAdjustment = preferenceAdjustment(item.slots(), explicitSlots, preferences);
        double sortScore = weatherAdjusted + hybridRetrievalAdjustment + preferenceAdjustment;
        double finalScore = clamp(sortScore);

        ActivityItem rankedItem = new ActivityItem(
                item.id(), item.sourceType(), item.ownerUserId(), item.name(), item.description(), item.slots(),
                item.validFrom(), item.validTo(), item.validStartTime(), item.validEndTime(),
                item.durationMinutes(), finalScore, sortScore);
        WeatherRecommendationContext.Status weatherStatus = weather == null || weather.status() == null
                ? WeatherRecommendationContext.Status.NOT_REQUESTED
                : weather.status();
        ActivityRankScore breakdown = new ActivityRankScore(
                item.id(), timeScore, weatherAdjustment, lexicalAdjustment,
                hybridRetrievalAdjustment, preferenceAdjustment, finalScore, weatherStatus);
        return new ScoredActivity(rankedItem, breakdown, sortScore);
    }

    private List<PreferenceFact> loadPreferences(Long userId) {
        if (userId == null || preferenceMemoryService == null) return List.of();
        try {
            return preferenceMemoryService.findActive(userId);
        } catch (RuntimeException error) {
            log.warn("Failed to load preference memory for userId={}", userId, error);
            return List.of();
        }
    }

    private double preferenceAdjustment(SlotBundle candidate,
                                        SlotBundle explicitSlots,
                                        List<PreferenceFact> preferences) {
        if (candidate == null || preferences == null || preferences.isEmpty()) return 0.0;
        double adjustment = 0.0;
        for (PreferenceFact fact : preferences) {
            if (fact == null || !Boolean.TRUE.equals(fact.getActive())) continue;
            List<String> candidateValues = slotValues(candidate, fact.getSlotName());
            if (!candidateValues.contains(fact.getSlotValue())) continue;
            boolean explicitlyRequested = slotValues(explicitSlots, fact.getSlotName())
                    .contains(fact.getSlotValue());
            if (fact.getPolarity() == PreferencePolarity.AVOID && explicitlyRequested) continue;
            adjustment += fact.getPolarity() == PreferencePolarity.AVOID ? -0.18 : 0.08;
        }
        return Math.max(-0.36, Math.min(0.16, adjustment));
    }

    private List<String> slotValues(SlotBundle slots, String slotName) {
        if (slots == null || slotName == null) return List.of();
        return switch (slotName) {
            case "city" -> slots.city();
            case "location" -> slots.location();
            case "experienceGoal" -> slots.experienceGoal();
            case "companion" -> slots.companion();
            case "budget" -> slots.budget();
            case "activityType" -> slots.activityType();
            case "style" -> slots.style();
            case "duration" -> slots.duration();
            case "feature" -> slots.feature();
            default -> List.of();
        };
    }

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
        if (features.contains("室内")) return clamp(baseScore * 0.88 + 0.12);
        if (features.contains("户外") || features.contains("室外")) return clamp(baseScore * 0.82);
        return baseScore;
    }

    private double clamp(double score) {
        return Math.max(0, Math.min(1, score));
    }

    private record ScoredActivity(ActivityItem activity, ActivityRankScore score, double sortScore) {}
}

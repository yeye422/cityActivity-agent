package com.city.service.activity;

import com.city.model.ActivityDiversityDecision;
import com.city.model.ActivityDiversityResult;
import com.city.model.ActivityItem;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 近似同分候选的多样性重排。
 * 相关性始终优先；只有与当前最高剩余分差不超过阈值时，才用活动类型、地点、时段覆盖打破同分。
 */
@Service
public class ActivityDiversityService {
    static final double NEAR_TIE_THRESHOLD = 0.03;
    private static final int MAX_RESULTS = 10;

    public ActivityDiversityResult rerank(List<ActivityItem> relevanceRanked) {
        if (relevanceRanked == null || relevanceRanked.isEmpty()) {
            return new ActivityDiversityResult(List.of(), List.of());
        }

        List<ActivityItem> remaining = relevanceRanked.stream()
                .filter(item -> item != null)
                .sorted(Comparator
                        .comparingDouble((ActivityItem item) -> item.rankingScore()).reversed()
                        .thenComparing((ActivityItem item) -> item.id(), Comparator.nullsLast(Long::compareTo)))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        List<ActivityItem> selected = new ArrayList<>();
        List<ActivityDiversityDecision> decisions = new ArrayList<>();
        Set<String> seenTypes = new LinkedHashSet<>();
        Set<String> seenLocations = new LinkedHashSet<>();
        Set<String> seenTimeBuckets = new LinkedHashSet<>();

        while (!remaining.isEmpty() && selected.size() < MAX_RESULTS) {
            double bestRemainingScore = remaining.getFirst().rankingScore();
            List<ActivityItem> eligible = remaining.stream()
                    .filter(item -> bestRemainingScore - item.rankingScore() <= NEAR_TIE_THRESHOLD + 1e-9)
                    .toList();

            ActivityItem chosen;
            if (selected.isEmpty()) {
                chosen = eligible.getFirst();
            } else {
                chosen = eligible.getFirst();
                for (int i = 1; i < eligible.size(); i++) {
                    ActivityItem candidate = eligible.get(i);
                    if (betterForDiversity(candidate, chosen, seenTypes, seenLocations, seenTimeBuckets)) {
                        chosen = candidate;
                    }
                }
            }

            List<String> reasons = selectionReasons(
                    chosen, selected.isEmpty(), bestRemainingScore, seenTypes, seenLocations, seenTimeBuckets);
            selected.add(chosen);
            decisions.add(new ActivityDiversityDecision(
                    chosen.id(), selected.size(), chosen.matchScore(), reasons));
            remember(chosen, seenTypes, seenLocations, seenTimeBuckets);
            remaining.remove(chosen);
        }

        return new ActivityDiversityResult(selected, decisions);
    }

    private boolean betterForDiversity(ActivityItem candidate,
                                       ActivityItem current,
                                       Set<String> seenTypes,
                                       Set<String> seenLocations,
                                       Set<String> seenTimeBuckets) {
        Novelty candidateNovelty = novelty(candidate, seenTypes, seenLocations, seenTimeBuckets);
        Novelty currentNovelty = novelty(current, seenTypes, seenLocations, seenTimeBuckets);
        if (candidateNovelty.newType() != currentNovelty.newType()) {
            return candidateNovelty.newType();
        }
        if (candidateNovelty.newLocation() != currentNovelty.newLocation()) {
            return candidateNovelty.newLocation();
        }
        if (candidateNovelty.newTimeBucket() != currentNovelty.newTimeBucket()) {
            return candidateNovelty.newTimeBucket();
        }
        if (Double.compare(candidate.rankingScore(), current.rankingScore()) != 0) {
            return candidate.rankingScore() > current.rankingScore();
        }
        return compareId(candidate.id(), current.id()) < 0;
    }

    private List<String> selectionReasons(ActivityItem item,
                                          boolean first,
                                          double bestRemainingScore,
                                          Set<String> seenTypes,
                                          Set<String> seenLocations,
                                          Set<String> seenTimeBuckets) {
        if (first) {
            return List.of("RELEVANCE_LEADER");
        }
        List<String> reasons = new ArrayList<>();
        if (item.rankingScore() + 1e-9 < bestRemainingScore) {
            reasons.add("NEAR_TIE_DIVERSITY");
        }
        Novelty novelty = novelty(item, seenTypes, seenLocations, seenTimeBuckets);
        if (novelty.newType()) {
            reasons.add("NEW_ACTIVITY_TYPE");
        }
        if (novelty.newLocation()) {
            reasons.add("NEW_LOCATION");
        }
        if (novelty.newTimeBucket()) {
            reasons.add("NEW_TIME_BUCKET");
        }
        if (reasons.isEmpty()) {
            reasons.add("RELEVANCE_TIE_BREAK");
        }
        return reasons;
    }

    private Novelty novelty(ActivityItem item,
                            Set<String> seenTypes,
                            Set<String> seenLocations,
                            Set<String> seenTimeBuckets) {
        boolean newType = item.slots() != null && hasNewValue(item.slots().activityType(), seenTypes);
        boolean newLocation = item.slots() != null && hasNewValue(item.slots().location(), seenLocations);
        String bucket = timeBucket(item.validStartTime());
        boolean newTimeBucket = bucket != null && !seenTimeBuckets.contains(bucket);
        return new Novelty(newType, newLocation, newTimeBucket);
    }

    private boolean hasNewValue(List<String> values, Set<String> seen) {
        return values != null && values.stream().anyMatch(value -> value != null && !value.isBlank() && !seen.contains(value));
    }

    private void remember(ActivityItem item,
                          Set<String> seenTypes,
                          Set<String> seenLocations,
                          Set<String> seenTimeBuckets) {
        if (item.slots() != null) {
            if (item.slots().activityType() != null) {
                seenTypes.addAll(item.slots().activityType());
            }
            if (item.slots().location() != null) {
                seenLocations.addAll(item.slots().location());
            }
        }
        String bucket = timeBucket(item.validStartTime());
        if (bucket != null) {
            seenTimeBuckets.add(bucket);
        }
    }

    private String timeBucket(LocalTime startTime) {
        if (startTime == null) {
            return null;
        }
        if (startTime.isBefore(LocalTime.NOON)) {
            return "MORNING";
        }
        if (startTime.isBefore(LocalTime.of(18, 0))) {
            return "AFTERNOON";
        }
        return "EVENING";
    }

    private int compareId(Long left, Long right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return left.compareTo(right);
    }

    private record Novelty(boolean newType, boolean newLocation, boolean newTimeBucket) {
    }
}

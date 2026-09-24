package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivitySearchRequest;
import com.city.model.ActivitySessionResponse;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.model.WeatherRecommendationContext;
import com.city.model.context.PlanningHorizon;
import com.city.model.retrieval.RetrievalRequest;
import com.city.model.retrieval.RetrievalResult;
import com.city.service.activity.ActivitySessionService;
import com.city.service.retrieval.RetrievalPipeline;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Planning 候选发现服务。
 *
 * <p>不切固定时间 bucket。Java 只按用户允许的绝对时间范围检索真实活动和 OPEN session；
 * 最终活动/session 组合完全由 PlanningAgent 决定。</p>
 */
@Service
public class ActivityPlanService {

    private static final int PLAN_CANDIDATE_LIMIT = 8;

    private final RetrievalPipeline retrievalPipeline;
    private final ActivitySessionService activitySessionService;

    public ActivityPlanService(RetrievalPipeline retrievalPipeline,
                               ActivitySessionService activitySessionService) {
        this.retrievalPipeline = Objects.requireNonNull(retrievalPipeline, "retrievalPipeline");
        this.activitySessionService = Objects.requireNonNull(activitySessionService, "activitySessionService");
    }

    /** 首次发现：对 horizon 中每个允许日期范围各取一批候选，再由 Registry 合并。 */
    public List<CandidateBatch> discoverHorizon(SourceMode sourceMode,
                                                Long userId,
                                                SlotBundle baseSlots,
                                                SlotBundle excludedSlots,
                                                PlanningHorizon horizon,
                                                WeatherRecommendationContext weather) {
        if (horizon == null || horizon.isEmpty()) return List.of();
        return horizon.ranges().stream()
                .map(range -> discoverRange(
                        sourceMode, userId, baseSlots, excludedSlots,
                        range, weather, "", List.of()))
                .toList();
    }

    /** Agent 发现局部时间缺口时调用；range 合法性由 Tool 在进入本方法前校验。 */
    public CandidateBatch discoverRange(SourceMode sourceMode,
                                        Long userId,
                                        SlotBundle baseSlots,
                                        SlotBundle excludedSlots,
                                        PlanningHorizon.Range range,
                                        WeatherRecommendationContext weather,
                                        String retrievalIntent,
                                        List<Long> excludeActivityIds) {
        Objects.requireNonNull(range, "range");
        if (!range.startAt().toLocalDate().equals(range.endAt().toLocalDate())) {
            throw new IllegalArgumentException("单次 planning candidate search 必须位于同一自然日");
        }

        SlotBundle safeSlots = baseSlots == null ? SlotBundle.empty() : baseSlots;
        SlotBundle safeExcluded = excludedSlots == null ? SlotBundle.empty() : excludedSlots;
        WeatherRecommendationContext safeWeather = weather == null
                ? WeatherRecommendationContext.inactive()
                : weather;

        TimeConstraint target = new TimeConstraint(
                range.toString(),
                range.startAt().toLocalDate(),
                range.endAt().toLocalDate(),
                range.startAt().toLocalTime(),
                range.endAt().toLocalTime(),
                null
        );
        ActivitySearchRequest request = new ActivitySearchRequest(
                sourceMode,
                userId,
                safeSlots,
                excludeActivityIds == null ? List.of() : List.copyOf(excludeActivityIds),
                target,
                safeExcluded
        );
        RetrievalResult retrieval = retrievalPipeline.retrieve(new RetrievalRequest(
                request,
                retrievalIntent == null ? "" : retrievalIntent.trim(),
                safeWeather,
                PLAN_CANDIDATE_LIMIT
        ));

        List<ActivityItem> retrieved = retrieval.finalCandidates();
        SessionLoad sessions = loadSessions(retrieved, range);
        List<ActivityItem> candidates = retrieved.stream()
                .filter(item -> item != null && item.id() != null)
                .filter(item -> !sessions.sessionBackedActivityIds().contains(item.id())
                        || sessions.sessionsByActivityId().containsKey(item.id()))
                .toList();
        return new CandidateBatch(range, candidates, sessions.sessionsByActivityId());
    }

    private SessionLoad loadSessions(
            List<ActivityItem> candidates,
            PlanningHorizon.Range range
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return new SessionLoad(Map.of(), java.util.Set.of());
        }

        Map<Long, List<ActivitySessionResponse>> result = new LinkedHashMap<>();
        java.util.Set<Long> sessionBacked = new java.util.LinkedHashSet<>();
        for (ActivityItem candidate : candidates) {
            if (candidate == null || candidate.id() == null) continue;
            List<ActivitySessionResponse> available;
            try {
                available = activitySessionService
                        .findAvailable(candidate.id(), range.startAt().toLocalDate())
                        .stream()
                        .filter(session -> "OPEN".equalsIgnoreCase(session.status()))
                        .filter(session -> session.remainingSeats() == null || session.remainingSeats() > 0)
                        .toList();
            } catch (RuntimeException ignored) {
                available = List.of();
            }
            if (!available.isEmpty()) sessionBacked.add(candidate.id());
            List<ActivitySessionResponse> inRange = available.stream()
                    .filter(session -> sessionWithinRange(session, range))
                    .toList();
            if (!inRange.isEmpty()) result.put(candidate.id(), inRange);
        }
        return new SessionLoad(Map.copyOf(result), java.util.Set.copyOf(sessionBacked));
    }

    private boolean sessionWithinRange(
            ActivitySessionResponse session,
            PlanningHorizon.Range range
    ) {
        if (session == null || session.startAt() == null || session.endAt() == null) return false;
        LocalDateTime start = session.startAt();
        LocalDateTime end = session.endAt();
        return !start.isBefore(range.startAt())
                && !end.isAfter(range.endAt())
                && start.isBefore(end);
    }

    private record SessionLoad(
            Map<Long, List<ActivitySessionResponse>> sessionsByActivityId,
            java.util.Set<Long> sessionBackedActivityIds
    ) {}

    public record CandidateBatch(
            PlanningHorizon.Range range,
            List<ActivityItem> candidates,
            Map<Long, List<ActivitySessionResponse>> sessionsByActivityId
    ) {
        public CandidateBatch {
            Objects.requireNonNull(range, "range");
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            sessionsByActivityId = sessionsByActivityId == null
                    ? Map.of()
                    : Map.copyOf(sessionsByActivityId);
        }
    }
}

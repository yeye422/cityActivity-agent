package com.city.service.retrieval;

import com.city.model.ActivityDiversityResult;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivityRankResult;
import com.city.model.ActivitySearchRequest;
import com.city.model.retrieval.RetrievalRequest;
import com.city.model.retrieval.RetrievalResult;
import com.city.service.activity.ActivityDiversityService;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RetrievalPipeline {
    private final ActivitySearchService activitySearchService;
    private final ActivityRankService activityRankService;
    private final ActivityDiversityService activityDiversityService;
    private final ActivityVectorRetriever activityVectorRetriever;
    private final ReciprocalRankFusionService rrfService;

    public RetrievalPipeline(ActivitySearchService activitySearchService,
                             ActivityRankService activityRankService,
                             ActivityDiversityService activityDiversityService) {
        this(activitySearchService, activityRankService, activityDiversityService, null, null);
    }

    @Autowired
    public RetrievalPipeline(ActivitySearchService activitySearchService,
                             ActivityRankService activityRankService,
                             ActivityDiversityService activityDiversityService,
                             ActivityVectorRetriever activityVectorRetriever,
                             ReciprocalRankFusionService rrfService) {
        this.activitySearchService = Objects.requireNonNull(activitySearchService, "activitySearchService");
        this.activityRankService = Objects.requireNonNull(activityRankService, "activityRankService");
        this.activityDiversityService = Objects.requireNonNull(activityDiversityService, "activityDiversityService");
        this.activityVectorRetriever = activityVectorRetriever;
        this.rrfService = rrfService;
    }

    public RetrievalResult retrieve(RetrievalRequest request) {
        Objects.requireNonNull(request, "request");
        ActivitySearchRequest searchRequest = request.searchRequest();

        List<ActivityItem> raw = activitySearchService.search(searchRequest);
        Map<Long, Double> lexicalScores = activityRankService.lexicalScores(raw, request.queryText());
        Map<Long, Double> vectorScores = activityVectorRetriever == null
                ? Map.of()
                : activityVectorRetriever.score(raw, request.queryText());

        ActivityRankRequest rankRequest = new ActivityRankRequest(
                raw, searchRequest.slots(), searchRequest.timeConstraint(),
                searchRequest.excludeActivityIds(), searchRequest.userId(), request.queryText());

        ActivityRankResult ranked;
        Map<Long, Double> hybridScores;
        List<Long> vectorRank = rankIds(vectorScores);
        if (vectorRank.isEmpty() || rrfService == null) {
            ranked = activityRankService.rank(rankRequest, request.weather());
            hybridScores = lexicalScores;
        } else {
            hybridScores = rrfService.fuse(rankIds(lexicalScores), vectorRank);
            ActivityRankRequest hybridRequest = new ActivityRankRequest(
                    raw, searchRequest.slots(), searchRequest.timeConstraint(),
                    searchRequest.excludeActivityIds(), searchRequest.userId(), null);
            ranked = activityRankService.rankWithRetrievalPrior(
                    hybridRequest, request.weather(), hybridScores);
        }

        ActivityDiversityResult diversified = activityDiversityService.rerank(ranked.ranked());
        List<ActivityItem> finalCandidates = diversified.ranked().stream()
                .limit(request.topK())
                .toList();

        return new RetrievalResult(
                raw, ranked.ranked(), finalCandidates, ranked.scores(), diversified.decisions(),
                lexicalScores, vectorScores, hybridScores);
    }

    private List<Long> rankIds(Map<Long, Double> scores) {
        if (scores == null || scores.isEmpty()) return List.of();
        return scores.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getValue() != null
                        && Double.isFinite(entry.getValue())
                        && entry.getValue() > 0.0)
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }
}

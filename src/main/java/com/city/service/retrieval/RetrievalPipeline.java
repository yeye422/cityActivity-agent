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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 推荐和规划共用的统一候选检索流水线。
 *
 * <p>当前阶段保持现有线上语义：ActivitySearchService 负责数据源、时间和九维硬条件过滤，
 * ActivityRankService 负责 BM25/时间/天气/长期偏好重排，ActivityDiversityService 负责近似同分多样性。
 * 后续 VectorRetriever、RRF 和专用 Reranker 只在本类内部演进，上层 Tool/Agent 不需要感知。</p>
 */
@Service
public class RetrievalPipeline {
    private final ActivitySearchService activitySearchService;
    private final ActivityRankService activityRankService;
    private final ActivityDiversityService activityDiversityService;

    public RetrievalPipeline(ActivitySearchService activitySearchService,
                             ActivityRankService activityRankService,
                             ActivityDiversityService activityDiversityService) {
        this.activitySearchService = Objects.requireNonNull(activitySearchService, "activitySearchService");
        this.activityRankService = Objects.requireNonNull(activityRankService, "activityRankService");
        this.activityDiversityService = Objects.requireNonNull(activityDiversityService, "activityDiversityService");
    }

    public RetrievalResult retrieve(RetrievalRequest request) {
        Objects.requireNonNull(request, "request");
        ActivitySearchRequest searchRequest = request.searchRequest();

        List<ActivityItem> raw = activitySearchService.search(searchRequest);
        ActivityRankRequest rankRequest = new ActivityRankRequest(
                raw,
                searchRequest.slots(),
                searchRequest.timeConstraint(),
                searchRequest.excludeActivityIds(),
                searchRequest.userId(),
                request.queryText()
        );
        ActivityRankResult ranked = activityRankService.rank(rankRequest, request.weather());
        ActivityDiversityResult diversified = activityDiversityService.rerank(ranked.ranked());
        List<ActivityItem> finalCandidates = diversified.ranked().stream()
                .limit(request.topK())
                .toList();

        return new RetrievalResult(
                raw,
                ranked.ranked(),
                finalCandidates,
                ranked.scores(),
                diversified.decisions()
        );
    }
}

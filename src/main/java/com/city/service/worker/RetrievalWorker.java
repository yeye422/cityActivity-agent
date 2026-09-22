package com.city.service.worker;

import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivitySearchRequest;
import com.city.model.WeatherRecommendationContext;
import com.city.model.agent.DiscoveryResult;
import com.city.service.activity.ActivityRankService;
import com.city.service.activity.ActivitySearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 统一候选检索 Worker。
 *
 * <p>当前阶段复用既有 ActivitySearchService 和 ActivityRankService，先完成普通推荐与 Planning
 * 的检索职责收敛；后续 P6 再在本 Worker 内统一 BM25、向量召回、RRF 与专用 Reranker。</p>
 */
@Component
public final class RetrievalWorker {
    private final ActivitySearchService activitySearchService;
    private final ActivityRankService activityRankService;

    @Autowired
    public RetrievalWorker(ActivitySearchService activitySearchService,
                           ActivityRankService activityRankService) {
        this.activitySearchService = Objects.requireNonNull(activitySearchService, "activitySearchService");
        this.activityRankService = Objects.requireNonNull(activityRankService, "activityRankService");
    }

    /**
     * 兼容过渡期只需要检索能力的旧调用方；需要排序时必须使用完整构造方式。
     */
    public RetrievalWorker(ActivitySearchService activitySearchService) {
        this.activitySearchService = Objects.requireNonNull(activitySearchService, "activitySearchService");
        this.activityRankService = null;
    }

    /**
     * 返回经过数据源、时间和硬条件过滤后的原始候选。
     * Planning 与普通推荐共享同一 Retrieval Worker 边界。
     */
    public List<ActivityItem> retrieveCandidates(ActivitySearchRequest request) {
        Objects.requireNonNull(request, "ActivitySearchRequest 不能为空");
        return activitySearchService.search(request);
    }

    /**
     * 检索并执行现有 Java 排序，供 Planning 等需要 TopK 候选的流程复用。
     * P6 后该入口内部可替换为统一 RetrievalPipeline，而上层调用方无需感知实现变化。
     */
    public List<ActivityItem> retrieveRanked(ActivitySearchRequest request,
                                             WeatherRecommendationContext weather,
                                             int topK) {
        Objects.requireNonNull(request, "ActivitySearchRequest 不能为空");
        if (activityRankService == null) {
            throw new IllegalStateException("当前 RetrievalWorker 未配置 ActivityRankService");
        }
        if (topK <= 0) return List.of();
        WeatherRecommendationContext safeWeather = weather == null
                ? WeatherRecommendationContext.inactive()
                : weather;
        List<ActivityItem> candidates = retrieveCandidates(request);
        return activityRankService.rank(
                        new ActivityRankRequest(
                                candidates,
                                request.slots(),
                                request.timeConstraint(),
                                request.excludeActivityIds()),
                        safeWeather)
                .ranked().stream()
                .filter(item -> item != null && item.id() != null)
                .limit(topK)
                .toList();
    }

    /** 普通推荐入口：候选检索同时生成实体证据。 */
    public DiscoveryResult retrieve(ActivitySearchRequest request) {
        Objects.requireNonNull(request, "ActivitySearchRequest 不能为空");
        return activitySearchService.discover(request);
    }

    public SourceResults retrievePersonalAndPublic(ActivitySearchRequest personal,
                                                   ActivitySearchRequest publicRequest) {
        Objects.requireNonNull(personal, "personal request 不能为空");
        Objects.requireNonNull(publicRequest, "public request 不能为空");
        CompletableFuture<DiscoveryResult> personalFuture = CompletableFuture.supplyAsync(
                () -> retrieve(personal));
        CompletableFuture<DiscoveryResult> publicFuture = CompletableFuture.supplyAsync(
                () -> retrieve(publicRequest));
        return new SourceResults(personalFuture.join(), publicFuture.join());
    }

    public record SourceResults(DiscoveryResult personal, DiscoveryResult publicResult) {
        public SourceResults {
            if (personal == null || publicResult == null) {
                throw new IllegalArgumentException("双源检索结果不能为空");
            }
        }
    }
}

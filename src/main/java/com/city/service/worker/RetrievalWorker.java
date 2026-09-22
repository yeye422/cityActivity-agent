package com.city.service.worker;

import com.city.model.ActivityItem;
import com.city.model.ActivitySearchRequest;
import com.city.model.agent.DiscoveryResult;
import com.city.service.activity.ActivitySearchService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 统一候选检索 Worker。
 *
 * <p>当前阶段复用既有 ActivitySearchService 和 DiscoveryResult，先完成 Discovery -> Retrieval
 * 的职责收敛；后续 P6 再在本 Worker 内统一 BM25、向量召回、RRF 与专用 Reranker。</p>
 */
@Component
public final class RetrievalWorker {
    private final ActivitySearchService activitySearchService;

    public RetrievalWorker(ActivitySearchService activitySearchService) {
        this.activitySearchService = Objects.requireNonNull(activitySearchService, "activitySearchService");
    }

    /**
     * 返回经过数据源、时间和硬条件过滤后的原始候选。
     * Planning 与普通推荐共享同一 Retrieval Worker 边界，排序仍由各自上层阶段负责。
     */
    public List<ActivityItem> retrieveCandidates(ActivitySearchRequest request) {
        Objects.requireNonNull(request, "ActivitySearchRequest 不能为空");
        return activitySearchService.search(request);
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

package com.city.service.worker;

import com.city.model.ActivitySearchRequest;
import com.city.model.agent.DiscoveryResult;
import com.city.service.activity.ActivitySearchService;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 统一候选检索 Worker。
 *
 * <p>当前阶段复用既有 ActivitySearchService 和 DiscoveryResult，先完成 Discovery -> Retrieval
 * 的职责收敛；后续 P6 再在本 Worker 内统一 BM25、向量召回、RRF 与专用 Reranker。</p>
 */
public final class RetrievalWorker {
    private final ActivitySearchService activitySearchService;

    public RetrievalWorker(ActivitySearchService activitySearchService) {
        this.activitySearchService = Objects.requireNonNull(activitySearchService, "activitySearchService");
    }

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

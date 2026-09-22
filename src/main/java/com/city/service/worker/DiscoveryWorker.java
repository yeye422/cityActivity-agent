package com.city.service.worker;

import com.city.model.ActivitySearchRequest;
import com.city.model.agent.DiscoveryResult;
import com.city.service.activity.ActivitySearchService;

import java.util.concurrent.CompletableFuture;

/** 活动发现 Worker；只读，并支持 PERSONAL/PUBLIC 并行预取。 */
public final class DiscoveryWorker {
    private final ActivitySearchService activitySearchService;

    public DiscoveryWorker(ActivitySearchService activitySearchService) {
        this.activitySearchService = activitySearchService;
    }

    public DiscoveryResult discover(ActivitySearchRequest request) {
        return activitySearchService.discover(request);
    }

    public SourceResults discoverPersonalAndPublic(ActivitySearchRequest personal,
                                                   ActivitySearchRequest publicRequest) {
        CompletableFuture<DiscoveryResult> personalFuture = CompletableFuture.supplyAsync(
                () -> discover(personal));
        CompletableFuture<DiscoveryResult> publicFuture = CompletableFuture.supplyAsync(
                () -> discover(publicRequest));
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

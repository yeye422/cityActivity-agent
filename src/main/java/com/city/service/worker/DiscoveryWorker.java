package com.city.service.worker;

import com.city.model.ActivitySearchRequest;
import com.city.model.agent.DiscoveryResult;
import com.city.service.activity.ActivitySearchService;

/**
 * @deprecated 主链路逐步迁移到 {@link RetrievalWorker}。保留该类仅用于兼容现有调用和测试。
 */
@Deprecated
public final class DiscoveryWorker {
    private final RetrievalWorker delegate;

    public DiscoveryWorker(ActivitySearchService activitySearchService) {
        this.delegate = new RetrievalWorker(activitySearchService);
    }

    public DiscoveryResult discover(ActivitySearchRequest request) {
        return delegate.retrieve(request);
    }

    public SourceResults discoverPersonalAndPublic(ActivitySearchRequest personal,
                                                   ActivitySearchRequest publicRequest) {
        RetrievalWorker.SourceResults results = delegate.retrievePersonalAndPublic(personal, publicRequest);
        return new SourceResults(results.personal(), results.publicResult());
    }

    public record SourceResults(DiscoveryResult personal, DiscoveryResult publicResult) {
        public SourceResults {
            if (personal == null || publicResult == null) {
                throw new IllegalArgumentException("双源检索结果不能为空");
            }
        }
    }
}

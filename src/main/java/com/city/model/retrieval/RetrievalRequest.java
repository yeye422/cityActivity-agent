package com.city.model.retrieval;

import com.city.model.ActivitySearchRequest;
import com.city.model.WeatherRecommendationContext;

import java.util.Objects;

/**
 * 统一 RetrievalPipeline 的过渡期请求模型。
 *
 * <p>第一阶段继续复用现有 ActivitySearchRequest，保证硬过滤行为不变；
 * queryText/retrievalIntent 只用于排序层的文本相关性。后续接入 Vector/RRF 时保持该 Pipeline 入口稳定。</p>
 */
public record RetrievalRequest(
        ActivitySearchRequest searchRequest,
        String queryText,
        WeatherRecommendationContext weather,
        int topK
) {
    public RetrievalRequest {
        searchRequest = Objects.requireNonNull(searchRequest, "searchRequest");
        queryText = queryText == null ? "" : queryText.trim();
        weather = weather == null ? WeatherRecommendationContext.inactive() : weather;
        topK = topK <= 0 ? 10 : topK;
    }
}

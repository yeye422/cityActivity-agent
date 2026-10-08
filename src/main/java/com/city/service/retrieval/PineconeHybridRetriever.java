package com.city.service.retrieval;

import com.city.model.ActivityItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pinecone 双通道召回：
 * 1) FTS text score -> Pinecone 服务端 BM25；
 * 2) dense_vector score -> 语义向量。
 *
 * <p>两路均只在 MySQL 已通过硬约束过滤的 candidateIds 内检索。</p>
 */
@Service
public class PineconeHybridRetriever {
    private static final Logger log = LoggerFactory.getLogger(PineconeHybridRetriever.class);

    private final PineconeDocumentClient pinecone;
    private final DashScopeEmbeddingClient embeddingClient;

    public PineconeHybridRetriever(PineconeDocumentClient pinecone,
                                   DashScopeEmbeddingClient embeddingClient) {
        this.pinecone = Objects.requireNonNull(pinecone, "pinecone");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
    }

    public Result retrieve(List<ActivityItem> candidates, String queryText) {
        if (queryText == null || queryText.isBlank() || candidates == null || candidates.isEmpty()) {
            return Result.empty();
        }
        List<Long> ids = candidates.stream()
                .filter(item -> item != null && item.id() != null)
                .map(ActivityItem::id)
                .distinct()
                .toList();
        if (ids.isEmpty() || !pinecone.available()) return Result.empty();

        int topK = Math.min(50, Math.max(1, ids.size()));
        Map<Long, Double> lexical = Map.of();
        Map<Long, Double> dense = Map.of();

        try {
            lexical = pinecone.searchText(queryText, ids, topK);
        } catch (RuntimeException error) {
            log.warn("Pinecone FTS channel unavailable; continue with remaining retrieval signals: {}",
                    error.getMessage());
        }

        if (embeddingClient.available()) {
            try {
                List<List<Double>> embeddings = embeddingClient.embed(List.of(queryText.trim()));
                if (!embeddings.isEmpty()) {
                    dense = pinecone.searchDense(embeddings.getFirst(), ids, topK);
                }
            } catch (RuntimeException error) {
                log.warn("Pinecone dense channel unavailable; continue with remaining retrieval signals: {}",
                        error.getMessage());
            }
        }

        return new Result(lexical, dense);
    }

    public record Result(
            Map<Long, Double> lexicalScores,
            Map<Long, Double> vectorScores
    ) {
        public Result {
            lexicalScores = lexicalScores == null ? Map.of() : Map.copyOf(lexicalScores);
            vectorScores = vectorScores == null ? Map.of() : Map.copyOf(vectorScores);
        }

        public static Result empty() {
            return new Result(Map.of(), Map.of());
        }
    }
}

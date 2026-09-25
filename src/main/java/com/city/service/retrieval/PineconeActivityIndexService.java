package com.city.service.retrieval;

import com.city.model.ActivityItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将 MySQL 中的活动事实同步为 Pinecone document index。 */
@Service
public class PineconeActivityIndexService {
    private static final Logger log = LoggerFactory.getLogger(PineconeActivityIndexService.class);
    private static final int EMBEDDING_BATCH_SIZE = 10;

    private final PineconeDocumentClient pinecone;
    private final DashScopeEmbeddingClient embeddingClient;
    private final ActivitySemanticDocumentBuilder documentBuilder;

    public PineconeActivityIndexService(PineconeDocumentClient pinecone,
                                        DashScopeEmbeddingClient embeddingClient,
                                        ActivitySemanticDocumentBuilder documentBuilder) {
        this.pinecone = Objects.requireNonNull(pinecone, "pinecone");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
        this.documentBuilder = Objects.requireNonNull(documentBuilder, "documentBuilder");
    }

    public boolean available() {
        return pinecone.available() && embeddingClient.available();
    }

    public void syncBestEffort(List<ActivityItem> activities) {
        if (!available() || activities == null || activities.isEmpty()) return;
        try {
            List<ActivityItem> items = activities.stream()
                    .filter(item -> item != null && item.id() != null)
                    .toList();
            for (int start = 0; start < items.size(); start += EMBEDDING_BATCH_SIZE) {
                List<ActivityItem> batch = items.subList(start, Math.min(start + EMBEDDING_BATCH_SIZE, items.size()));
                List<String> documents = batch.stream().map(documentBuilder::build).toList();
                List<List<Double>> embeddings = embeddingClient.embed(documents);
                if (embeddings.size() != batch.size()) {
                    throw new IllegalStateException("Embedding batch size mismatch while syncing Pinecone");
                }
                List<PineconeDocumentClient.Document> payload = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    ActivityItem item = batch.get(i);
                    payload.add(new PineconeDocumentClient.Document(
                            item.id(),
                            documents.get(i),
                            embeddings.get(i),
                            item.sourceType() == null ? "" : item.sourceType().name(),
                            item.ownerUserId()
                    ));
                }
                pinecone.upsert(payload);
            }
        } catch (RuntimeException error) {
            log.warn("Failed to sync activity documents to Pinecone; retrieval will degrade gracefully", error);
        }
    }

    public void syncBestEffort(ActivityItem activity) {
        if (activity != null) syncBestEffort(List.of(activity));
    }

    public void deleteBestEffort(Long activityId) {
        if (!pinecone.available() || activityId == null) return;
        try {
            pinecone.delete(activityId);
        } catch (RuntimeException error) {
            log.warn("Failed to delete activity {} from Pinecone", activityId, error);
        }
    }
}

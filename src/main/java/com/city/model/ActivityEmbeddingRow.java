package com.city.model;

import lombok.Data;

import java.time.LocalDateTime;

/** activity_item 对应的持久化语义向量快照。 */
@Data
public class ActivityEmbeddingRow {
    private Long activityId;
    private String sourceHash;
    private String model;
    private Integer dimensions;
    private String embeddingJson;
    private LocalDateTime updatedAt;
}

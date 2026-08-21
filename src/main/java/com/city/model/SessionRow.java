package com.city.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SessionRow {
    private String id;
    private Long userId;
    private String phase;
    private String slots;
    private String lastRecommendedActivityIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}





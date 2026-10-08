package com.city.model;

import lombok.Data;

import java.time.LocalDateTime;

/** /chat 请求幂等记录。 */
@Data
public class ChatRequestIdempotencyRow {
    private Long id;
    private Long userId;
    private String idempotencyKey;
    private String requestHash;
    private String status;
    private String responseJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

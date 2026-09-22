package com.city.model;

import com.city.enums.DegradationReason;
import com.city.enums.ErrorCode;

import java.time.Instant;

/** 所有 HTTP 异常使用的统一响应合同。 */
public record ApiErrorResponse(
        ErrorCode code,
        String message,
        boolean recoverable,
        DegradationReason degradationReason,
        String path,
        Instant occurredAt
) {
}

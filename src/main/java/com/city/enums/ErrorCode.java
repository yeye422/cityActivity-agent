package com.city.enums;

import org.springframework.http.HttpStatus;

/** CityFlow 对外稳定错误码。 */
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, true),
    BUSINESS_RULE_VIOLATION(HttpStatus.BAD_REQUEST, true),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, true),
    STATE_CONFLICT(HttpStatus.CONFLICT, true),
    AGENT_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, true),
    EXTERNAL_DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
    DATA_INTEGRITY_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false);

    private final HttpStatus httpStatus;
    private final boolean recoverable;

    ErrorCode(HttpStatus httpStatus, boolean recoverable) {
        this.httpStatus = httpStatus;
        this.recoverable = recoverable;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public boolean recoverable() {
        return recoverable;
    }
}

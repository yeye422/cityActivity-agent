package com.city.exception;

import com.city.enums.DegradationReason;
import com.city.enums.ErrorCode;

public class CityException extends RuntimeException {
    private final ErrorCode code;
    private final DegradationReason degradationReason;

    public CityException(String message) {
        this(ErrorCode.BUSINESS_RULE_VIOLATION, message, DegradationReason.NONE, null);
    }

    public CityException(String message, Throwable cause) {
        this(ErrorCode.INTERNAL_ERROR, message, DegradationReason.NONE, cause);
    }

    public CityException(ErrorCode code, String message) {
        this(code, message, DegradationReason.NONE, null);
    }

    public CityException(ErrorCode code, String message, DegradationReason degradationReason) {
        this(code, message, degradationReason, null);
    }

    public CityException(ErrorCode code,
                         String message,
                         DegradationReason degradationReason,
                         Throwable cause) {
        super(message, cause);
        this.code = code == null ? ErrorCode.INTERNAL_ERROR : code;
        this.degradationReason = degradationReason == null ? DegradationReason.NONE : degradationReason;
    }

    public ErrorCode code() {
        return code;
    }

    public DegradationReason degradationReason() {
        return degradationReason;
    }
}




package com.city.exception;

import com.city.enums.DegradationReason;
import com.city.enums.ErrorCode;
import com.city.model.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice(basePackages = "com.city")
public class CityExceptionHandler {
    @ExceptionHandler(CityException.class)
    public ResponseEntity<ApiErrorResponse> handleCityException(CityException error, HttpServletRequest request) {
        ErrorCode code = error.code();
        return ResponseEntity.status(code.httpStatus()).body(new ApiErrorResponse(
                code,
                error.getMessage(),
                code.recoverable(),
                error.degradationReason(),
                request.getRequestURI(),
                Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiErrorResponse(
                ErrorCode.INTERNAL_ERROR,
                "服务异常",
                false,
                DegradationReason.NONE,
                request.getRequestURI(),
                Instant.now()));
    }
}

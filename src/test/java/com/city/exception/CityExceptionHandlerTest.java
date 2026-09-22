package com.city.exception;

import com.city.enums.DegradationReason;
import com.city.enums.ErrorCode;
import com.city.model.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CityExceptionHandlerTest {
    private final CityExceptionHandler handler = new CityExceptionHandler();

    @Test
    void cityExceptionShouldExposeStableCodeAndDegradationReason() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/city/chat");
        CityException error = new CityException(
                ErrorCode.AGENT_BUDGET_EXCEEDED,
                "调用预算已用完",
                DegradationReason.CALL_BUDGET_EXCEEDED);

        ResponseEntity<ApiErrorResponse> response = handler.handleCityException(error, request);

        assertEquals(429, response.getStatusCode().value());
        assertEquals(ErrorCode.AGENT_BUDGET_EXCEEDED, response.getBody().code());
        assertEquals(DegradationReason.CALL_BUDGET_EXCEEDED, response.getBody().degradationReason());
        assertEquals("/api/v1/city/chat", response.getBody().path());
    }

    @Test
    void unknownExceptionShouldNotLeakInternalMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/city/test");

        ResponseEntity<ApiErrorResponse> response = handler.handleException(
                new IllegalStateException("database-password-secret"), request);

        assertEquals(500, response.getStatusCode().value());
        assertEquals("服务异常", response.getBody().message());
        assertFalse(response.getBody().recoverable());
    }
}

package com.city.service.idempotency;

import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.mapper.ChatRequestIdempotencyMapper;
import com.city.model.ChatRequest;
import com.city.model.ChatRequestIdempotencyRow;
import com.city.model.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatRequestIdempotencyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ChatRequest request = new ChatRequest(null, "周末想约会", SourceMode.PUBLIC, null);

    @Test
    void missingKeyShouldKeepOriginalBehavior() {
        ChatRequestIdempotencyMapper mapper = mock(ChatRequestIdempotencyMapper.class);
        ChatRequestIdempotencyService service = new ChatRequestIdempotencyService(mapper, objectMapper);
        ChatResponse expected = ChatResponse.answer("s1", "t1", "ok", List.of(), "WAIT_USER");

        ChatResponse actual = service.execute(1L, null, request, () -> expected);

        assertSame(expected, actual);
        verifyNoInteractions(mapper);
    }

    @Test
    void ownerShouldExecuteOnceAndPersistSuccessSnapshot() {
        ChatRequestIdempotencyMapper mapper = mock(ChatRequestIdempotencyMapper.class);
        when(mapper.insertPending(any())).thenReturn(1);
        when(mapper.markSuccess(any(), anyString(), anyString(), anyString())).thenReturn(1);
        ChatRequestIdempotencyService service = new ChatRequestIdempotencyService(mapper, objectMapper);
        AtomicInteger calls = new AtomicInteger();
        ChatResponse expected = ChatResponse.answer("s1", "t1", "ok", List.of(), "WAIT_USER");

        ChatResponse actual = service.execute(1L, "key-1", request, () -> {
            calls.incrementAndGet();
            return expected;
        });

        assertSame(expected, actual);
        assertEquals(1, calls.get());
        verify(mapper).markSuccess(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("key-1"), anyString(), anyString());
    }

    @Test
    void completedDuplicateShouldReturnStoredResponseWithoutExecutingAgain() throws Exception {
        ChatRequestIdempotencyMapper mapper = mock(ChatRequestIdempotencyMapper.class);
        AtomicReference<ChatRequestIdempotencyRow> existing = new AtomicReference<>();
        ChatResponse cached = ChatResponse.answer("s1", "trace-old", "cached", List.of(), "WAIT_USER");
        String cachedJson = objectMapper.writeValueAsString(cached);

        when(mapper.insertPending(any())).thenAnswer(invocation -> {
            ChatRequestIdempotencyRow attempted = invocation.getArgument(0);
            ChatRequestIdempotencyRow row = copyClaim(attempted, "SUCCESS");
            row.setResponseJson(cachedJson);
            existing.set(row);
            return 0;
        });
        when(mapper.find(1L, "key-1")).thenAnswer(ignored -> existing.get());
        ChatRequestIdempotencyService service = new ChatRequestIdempotencyService(mapper, objectMapper);
        AtomicInteger calls = new AtomicInteger();

        ChatResponse actual = service.execute(1L, "key-1", request, () -> {
            calls.incrementAndGet();
            return ChatResponse.answer("s2", "new", "should-not-run", List.of(), "WAIT_USER");
        });

        assertEquals("trace-old", actual.traceId());
        assertEquals("cached", actual.speechText());
        assertEquals(0, calls.get());
        verify(mapper, never()).markSuccess(any(), anyString(), anyString(), anyString());
    }

    @Test
    void pendingDuplicateShouldFailClosedWithoutExecutingAgain() {
        ChatRequestIdempotencyMapper mapper = mock(ChatRequestIdempotencyMapper.class);
        AtomicReference<ChatRequestIdempotencyRow> existing = new AtomicReference<>();
        when(mapper.insertPending(any())).thenAnswer(invocation -> {
            ChatRequestIdempotencyRow attempted = invocation.getArgument(0);
            existing.set(copyClaim(attempted, "PENDING"));
            return 0;
        });
        when(mapper.find(1L, "key-1")).thenAnswer(ignored -> existing.get());
        ChatRequestIdempotencyService service = new ChatRequestIdempotencyService(mapper, objectMapper);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(CityException.class,
                () -> service.execute(1L, "key-1", request, () -> {
                    calls.incrementAndGet();
                    return ChatResponse.answer("s1", "should-not-run", List.of(), "WAIT_USER");
                }));

        assertEquals(0, calls.get());
        verify(mapper, never()).deletePending(any(), anyString(), anyString());
    }

    @Test
    void sameKeyWithDifferentRequestShouldBeRejected() {
        ChatRequestIdempotencyMapper mapper = mock(ChatRequestIdempotencyMapper.class);
        when(mapper.insertPending(any())).thenReturn(0);
        ChatRequestIdempotencyRow existing = new ChatRequestIdempotencyRow();
        existing.setRequestHash("definitely-different-hash");
        existing.setStatus("SUCCESS");
        when(mapper.find(1L, "key-1")).thenReturn(existing);
        ChatRequestIdempotencyService service = new ChatRequestIdempotencyService(mapper, objectMapper);

        assertThrows(CityException.class,
                () -> service.execute(1L, "key-1", request,
                        () -> ChatResponse.answer("s1", "x", List.of(), "WAIT_USER")));
    }

    @Test
    void failedOwnerShouldReleasePendingClaimForRetry() {
        ChatRequestIdempotencyMapper mapper = mock(ChatRequestIdempotencyMapper.class);
        when(mapper.insertPending(any())).thenReturn(1);
        ChatRequestIdempotencyService service = new ChatRequestIdempotencyService(mapper, objectMapper);

        assertThrows(IllegalStateException.class,
                () -> service.execute(1L, "key-1", request, () -> {
                    throw new IllegalStateException("agent failed");
                }));

        verify(mapper).deletePending(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("key-1"), anyString());
    }

    private ChatRequestIdempotencyRow copyClaim(ChatRequestIdempotencyRow attempted, String status) {
        ChatRequestIdempotencyRow row = new ChatRequestIdempotencyRow();
        row.setUserId(attempted.getUserId());
        row.setIdempotencyKey(attempted.getIdempotencyKey());
        row.setRequestHash(attempted.getRequestHash());
        row.setStatus(status);
        return row;
    }
}

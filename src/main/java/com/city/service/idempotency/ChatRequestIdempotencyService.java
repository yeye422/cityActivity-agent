package com.city.service.idempotency;

import com.city.exception.CityException;
import com.city.mapper.ChatRequestIdempotencyMapper;
import com.city.model.ChatRequest;
import com.city.model.ChatRequestIdempotencyRow;
import com.city.model.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * /chat 的持久化幂等边界。
 *
 * <p>没有 Idempotency-Key 时完全保持原行为；有 key 时先用 userId + key 原子 claim。
 * SUCCESS 直接返回持久化响应，PENDING 阻断重复执行，同一 key 绑定不同请求指纹会明确拒绝。</p>
 */
@Service
public class ChatRequestIdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(ChatRequestIdempotencyService.class);
    private static final int MAX_KEY_LENGTH = 128;
    private static final Duration STALE_PENDING_AFTER = Duration.ofMinutes(5);

    private final ChatRequestIdempotencyMapper mapper;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;

    public ChatRequestIdempotencyService(ChatRequestIdempotencyMapper mapper, ObjectMapper objectMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public ChatResponse execute(Long userId,
                                String idempotencyKey,
                                ChatRequest request,
                                Supplier<ChatResponse> action) {
        Objects.requireNonNull(action, "action");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        if (userId == null || userId <= 0) {
            throw new CityException("用户 ID 不合法");
        }
        String key = normalizeKey(idempotencyKey);
        String requestHash = fingerprint(request);
        return executeClaimed(userId, key, requestHash, action, true);
    }

    private ChatResponse executeClaimed(Long userId,
                                        String key,
                                        String requestHash,
                                        Supplier<ChatResponse> action,
                                        boolean allowStaleReclaim) {
        ChatRequestIdempotencyRow claim = new ChatRequestIdempotencyRow();
        claim.setUserId(userId);
        claim.setIdempotencyKey(key);
        claim.setRequestHash(requestHash);
        if (mapper.insertPending(claim) == 1) {
            return executeOwnedClaim(userId, key, requestHash, action);
        }

        ChatRequestIdempotencyRow existing = mapper.find(userId, key);
        if (existing == null) {
            throw new CityException("幂等请求状态暂时不可用，请稍后重试");
        }
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new CityException("同一个 Idempotency-Key 不能用于不同的聊天请求");
        }
        if ("SUCCESS".equals(existing.getStatus())) {
            return deserialize(existing.getResponseJson());
        }
        if ("PENDING".equals(existing.getStatus())) {
            if (allowStaleReclaim && isStale(existing.getUpdatedAt())) {
                int deleted = mapper.deletePending(userId, key, requestHash);
                if (deleted == 1) {
                    return executeClaimed(userId, key, requestHash, action, false);
                }
            }
            throw new CityException("相同聊天请求正在处理中，请使用同一 Idempotency-Key 稍后重试");
        }
        throw new CityException("未知幂等请求状态: " + existing.getStatus());
    }

    private ChatResponse executeOwnedClaim(Long userId,
                                           String key,
                                           String requestHash,
                                           Supplier<ChatResponse> action) {
        try {
            ChatResponse response = action.get();
            String json = objectMapper.writeValueAsString(response);
            int updated = mapper.markSuccess(userId, key, requestHash, json);
            if (updated != 1) {
                // 业务响应已经完成，不能因为幂等结果落库异常反向诱导客户端重试并重复执行业务。
                log.warn("Chat idempotency response was produced but SUCCESS snapshot was not updated: userId={}, key={}",
                        userId, key);
            }
            return response;
        } catch (RuntimeException error) {
            mapper.deletePending(userId, key, requestHash);
            throw error;
        } catch (Exception error) {
            mapper.deletePending(userId, key, requestHash);
            throw new CityException("聊天幂等响应序列化失败", error);
        }
    }

    private ChatResponse deserialize(String json) {
        if (json == null || json.isBlank()) {
            throw new CityException("幂等响应快照缺失");
        }
        try {
            return objectMapper.readValue(json, ChatResponse.class);
        } catch (Exception error) {
            throw new CityException("幂等响应快照损坏", error);
        }
    }

    private boolean isStale(LocalDateTime updatedAt) {
        return updatedAt != null && updatedAt.isBefore(LocalDateTime.now().minus(STALE_PENDING_AFTER));
    }

    private String normalizeKey(String value) {
        String key = value.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw new CityException("Idempotency-Key 长度不能超过 " + MAX_KEY_LENGTH);
        }
        return key;
    }

    private String fingerprint(ChatRequest request) {
        try {
            byte[] payload = canonicalMapper.writeValueAsBytes(request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        } catch (Exception error) {
            throw new CityException("聊天请求无法生成幂等指纹", error);
        }
    }
}

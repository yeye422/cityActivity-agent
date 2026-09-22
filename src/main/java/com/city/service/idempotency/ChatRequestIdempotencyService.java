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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * /chat 的持久化幂等边界。
 *
 * <p>没有 Idempotency-Key 时完全保持原行为；有 key 时先用 userId + key 原子 claim。
 * SUCCESS 直接返回持久化响应，PENDING 阻断重复执行，同一 key 绑定不同请求指纹会明确拒绝。</p>
 *
 * <p>PENDING 不做基于时间的自动回收：进程崩溃时无法判断业务是否已经提交，自动重跑可能产生
 * 重复 SessionState/消息/Tool 副作用。只有 action 明确抛异常时才释放本次 claim。</p>
 */
@Service
public class ChatRequestIdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(ChatRequestIdempotencyService.class);
    private static final int MAX_KEY_LENGTH = 128;

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
        return executeClaimed(userId, key, requestHash, action);
    }

    private ChatResponse executeClaimed(Long userId,
                                        String key,
                                        String requestHash,
                                        Supplier<ChatResponse> action) {
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
            throw new CityException("相同聊天请求正在处理中或上次执行状态未知；为避免重复执行，不会自动重跑该 Idempotency-Key");
        }
        throw new CityException("未知幂等请求状态: " + existing.getStatus());
    }

    private ChatResponse executeOwnedClaim(Long userId,
                                           String key,
                                           String requestHash,
                                           Supplier<ChatResponse> action) {
        ChatResponse response;
        try {
            response = action.get();
        } catch (RuntimeException error) {
            safeReleasePending(userId, key, requestHash);
            throw error;
        }

        final String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (Exception error) {
            // 业务已经执行成功，此时绝不能释放 claim，否则客户端重试可能再次执行 Agent。
            log.warn("Chat succeeded but idempotency response serialization failed; keep claim PENDING to prevent duplicate execution: userId={}, key={}",
                    userId, key, error);
            return response;
        }

        try {
            int updated = mapper.markSuccess(userId, key, requestHash, json);
            if (updated != 1) {
                log.warn("Chat idempotency response was produced but SUCCESS snapshot was not updated: userId={}, key={}",
                        userId, key);
            }
        } catch (RuntimeException error) {
            log.warn("Chat succeeded but idempotency SUCCESS persistence failed; keep claim to prevent duplicate execution: userId={}, key={}",
                    userId, key, error);
        }
        return response;
    }

    private void safeReleasePending(Long userId, String key, String requestHash) {
        try {
            mapper.deletePending(userId, key, requestHash);
        } catch (RuntimeException releaseError) {
            log.warn("Failed to release failed chat idempotency claim: userId={}, key={}",
                    userId, key, releaseError);
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

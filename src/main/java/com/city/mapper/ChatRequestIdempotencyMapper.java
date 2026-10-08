package com.city.mapper;

import com.city.model.ChatRequestIdempotencyRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChatRequestIdempotencyMapper {
    int insertPending(ChatRequestIdempotencyRow row);

    ChatRequestIdempotencyRow find(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    int markSuccess(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("responseJson") String responseJson
    );

    int deletePending(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash
    );
}

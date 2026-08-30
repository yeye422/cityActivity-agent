package com.city.mapper;

import com.city.model.FeedbackRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FeedbackMapper {
    int insert(
            @Param("userId") Long userId,
            @Param("traceId") String traceId,
            @Param("sessionId") String sessionId,
            @Param("itemId") Long itemId,
            @Param("action") String action,
            @Param("rating") Integer rating,
            @Param("reason") String reason
    );

    List<FeedbackRow> findBySessions(
            @Param("userId") Long userId,
            @Param("sessionIds") List<String> sessionIds,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );
}

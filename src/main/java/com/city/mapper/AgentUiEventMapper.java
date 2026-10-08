package com.city.mapper;

import com.city.model.AgentUiEventRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentUiEventMapper {
    int insertIgnore(AgentUiEventRow row);

    Long findCursorId(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            @Param("traceId") String traceId,
            @Param("eventSeq") Integer eventSeq
    );

    List<AgentUiEventRow> findAfterId(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    List<AgentUiEventRow> findRecent(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            @Param("limit") int limit
    );
}

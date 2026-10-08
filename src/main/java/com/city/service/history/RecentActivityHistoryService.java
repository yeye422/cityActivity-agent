package com.city.service.history;

import com.city.mapper.SessionMapper;
import com.city.model.ActivityItem;
import com.city.model.SessionRow;
import com.city.model.tool.RecentActivityHistoryToolResult;
import com.city.service.activity.ActivityService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 基于持久化 SessionState 聚合用户跨会话近期推荐历史。 */
@Service
public class RecentActivityHistoryService {
    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {};
    private static final int MAX_SESSION_SNAPSHOTS = 12;

    private final SessionMapper sessionMapper;
    private final ActivityService activityService;
    private final ObjectMapper objectMapper;

    public RecentActivityHistoryService(SessionMapper sessionMapper,
                                        ActivityService activityService,
                                        ObjectMapper objectMapper) {
        this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
        this.activityService = Objects.requireNonNull(activityService, "activityService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public RecentActivityHistoryToolResult findRecent(Long userId, int maxItems) {
        int safeLimit = Math.max(1, Math.min(12, maxItems));
        List<SessionRow> sessions = sessionMapper.listRecentRecommendationSessions(
                userId, MAX_SESSION_SNAPSHOTS);

        LinkedHashMap<Long, Boolean> orderedIds = new LinkedHashMap<>();
        for (SessionRow session : sessions == null ? List.<SessionRow>of() : sessions) {
            List<Long> ids = parseIds(session == null ? null : session.getLastRecommendedActivityIds());
            for (int i = ids.size() - 1; i >= 0 && orderedIds.size() < safeLimit; i--) {
                Long id = ids.get(i);
                if (id != null) orderedIds.putIfAbsent(id, Boolean.TRUE);
            }
            if (orderedIds.size() >= safeLimit) break;
        }
        if (orderedIds.isEmpty()) return new RecentActivityHistoryToolResult(List.of());

        Map<Long, ActivityItem> accessible = new LinkedHashMap<>();
        for (ActivityItem item : activityService.findPublicActivities()) {
            if (item != null && item.id() != null) accessible.putIfAbsent(item.id(), item);
        }
        for (ActivityItem item : activityService.findPersonalActivities(userId)) {
            if (item != null && item.id() != null) accessible.putIfAbsent(item.id(), item);
        }

        List<RecentActivityHistoryToolResult.HistoryItem> result = new ArrayList<>();
        for (Long id : orderedIds.keySet()) {
            ActivityItem item = accessible.get(id);
            if (item != null) result.add(RecentActivityHistoryToolResult.from(item));
        }
        return new RecentActivityHistoryToolResult(List.copyOf(result));
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Long> parsed = objectMapper.readValue(json, LONG_LIST);
            return parsed == null ? List.of() : parsed;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}

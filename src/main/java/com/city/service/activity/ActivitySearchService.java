package com.city.service.activity;

import com.city.exception.CityException;
import com.city.model.ActivityItem;
import com.city.model.ActivitySearchRequest;
import com.city.enums.SourceMode;
import com.city.mapper.ActivitySessionMapper;
import com.city.model.TimeConstraint;
import com.city.model.agent.AgentResult;
import com.city.model.agent.DiscoveryResult;
import com.city.model.agent.EvidenceRef;
import com.city.service.agent.EvidenceRefFactory;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * 活动检索服务（Orchestrator 推荐流水线第一层）。
 * 按 sourceMode、userId、slots 从 DB 召回候选，不负责最终重排和 excludeActivityIds 过滤。
 */
@Service
public class ActivitySearchService {
    private static final Logger log = LoggerFactory.getLogger(ActivitySearchService.class);

    /** 底层活动服务，封装 MyBatis JSON_OVERLAPS 检索。 */
    private final ActivityService activityService;
    private final ActivitySessionMapper activitySessionMapper;
    private final EvidenceRefFactory evidenceRefFactory = new EvidenceRefFactory();

    /** 构造器注入 ActivityService。 */
    public ActivitySearchService(ActivityService activityService, ActivitySessionMapper activitySessionMapper) {
        this.activityService = activityService;
        this.activitySessionMapper = activitySessionMapper;
    }

    /**
     * 执行数据源隔离检索。
     * 由 Orchestrator#completeRecommendation 调用；excludeActivityIds 在 ActivityRankService 层过滤。
     */
    public List<ActivityItem> search(ActivitySearchRequest request) {
        // 请求体或 sourceMode 为空时抛异常
        if (request == null || request.sourceMode() == null) {
            throw new CityException("sourceMode 不能为空");
        }

        // PERSONAL 模式必须提供 userId，否则无法查个人库
        if (request.sourceMode() == SourceMode.PERSONAL && request.userId() == null) {
            throw new CityException("PERSONAL 模式必须提供 userId");
        }

        // ActivityService.search：MySQL JSON_OVERLAPS
        List<ActivityItem> candidates = activityService.search(
                request.sourceMode(), request.userId(), request.slots(), request.timeConstraint(), request.excludedSlots());
        return filterUnavailableSessions(candidates, request.timeConstraint());
    }

    /** 返回候选的同时生成可校验实体 ID 与证据引用，供 Supervisor 和 Trace 使用。 */
    public DiscoveryResult discover(ActivitySearchRequest request) {
        List<ActivityItem> candidates = search(request);
        List<EvidenceRef> evidenceRefs = candidates.stream()
                .filter(item -> item != null && item.id() != null)
                .map(evidenceRefFactory::activity)
                .toList();
        Set<Long> verifiedIds = candidates.stream()
                .filter(item -> item != null && item.id() != null)
                .map(ActivityItem::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        AgentResult result = new AgentResult(
                AgentResult.Status.COMPLETED,
                "已验证活动候选 " + verifiedIds.size() + " 个",
                verifiedIds,
                Set.of(),
                evidenceRefs,
                List.of(),
                Map.of("candidateCount", verifiedIds.size()));
        return new DiscoveryResult(candidates, result);
    }

    /** 有场次的活动必须命中一个可报名场次；长期活动（无场次）继续由 validFrom/validTo 处理。 */
    private List<ActivityItem> filterUnavailableSessions(List<ActivityItem> candidates, TimeConstraint time) {
        if (candidates == null || candidates.isEmpty() || time == null || !time.hasConstraint()) return candidates;
        List<Long> ids = candidates.stream().map(ActivityItem::id).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return candidates;
        try {
            Set<Long> withSessions = Set.copyOf(activitySessionMapper.findActivityIdsWithSessions(ids));
            if (withSessions.isEmpty()) return candidates;
            Set<Long> available = Set.copyOf(activitySessionMapper.findAvailableActivityIds(
                    ids, time.dateStart(), time.dateEnd(), time.startTime(), time.endTime()));
            return candidates.stream().filter(item -> !withSessions.contains(item.id()) || available.contains(item.id())).toList();
        } catch (Exception error) {
            // 未执行场次迁移时不阻断旧活动库；日志保留明确修复线索。
            log.warn("场次可用性过滤不可用，已回退到活动有效期过滤: {}", error.getMessage());
            return candidates;
        }
    }
}

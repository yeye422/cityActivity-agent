package com.city.service.activity;

import com.city.exception.CityException;
import com.city.model.ActivityItem;
import com.city.model.ActivitySearchRequest;
import com.city.enums.SourceMode;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 活动检索服务（Orchestrator 推荐流水线第一层）。
 * 按 sourceMode、userId、slots 从 DB 召回候选，不负责最终重排和 excludeActivityIds 过滤。
 */
@Service
public class ActivitySearchService {

    /** 底层活动服务，封装 MyBatis JSON_OVERLAPS 检索。 */
    private final ActivityService activityService;

    /** 构造器注入 ActivityService。 */
    public ActivitySearchService(ActivityService activityService) {
        this.activityService = activityService;
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
        return activityService.search(request.sourceMode(), request.userId(), request.slots());
    }
}

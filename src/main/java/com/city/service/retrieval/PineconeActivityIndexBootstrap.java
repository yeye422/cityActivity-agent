package com.city.service.retrieval;

import com.city.model.ActivityItem;
import com.city.service.activity.ActivityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/** 应用启动时把现有活动同步到 Pinecone；失败不会阻断主服务启动。 */
@Component
public class PineconeActivityIndexBootstrap {
    private static final Logger log = LoggerFactory.getLogger(PineconeActivityIndexBootstrap.class);

    private final ActivityService activityService;
    private final PineconeActivityIndexService indexService;

    public PineconeActivityIndexBootstrap(ActivityService activityService,
                                          PineconeActivityIndexService indexService) {
        this.activityService = activityService;
        this.indexService = indexService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (!indexService.available()) return;
        try {
            List<ActivityItem> activities = activityService.findAllActiveActivities();
            indexService.syncBestEffort(activities);
            log.info("Pinecone activity index startup sync requested: {} activities", activities.size());
        } catch (RuntimeException error) {
            log.warn("Pinecone startup sync failed; application continues without blocking chat", error);
        }
    }
}

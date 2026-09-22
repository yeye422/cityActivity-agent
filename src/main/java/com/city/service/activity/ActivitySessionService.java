package com.city.service.activity;

import com.city.mapper.ActivitySessionMapper;
import com.city.model.ActivitySessionResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** 第二层推荐：用户选定活动后，再列出可参加的场次与举办地点。 */
@Service
public class ActivitySessionService {
    private final ActivitySessionMapper activitySessionMapper;

    public ActivitySessionService(ActivitySessionMapper activitySessionMapper) {
        this.activitySessionMapper = activitySessionMapper;
    }

    public List<ActivitySessionResponse> findAvailable(Long activityId, LocalDate date) {
        return activitySessionMapper.findAvailableByActivityId(activityId, date).stream()
                .map(row -> row.toResponse())
                .toList();
    }
}

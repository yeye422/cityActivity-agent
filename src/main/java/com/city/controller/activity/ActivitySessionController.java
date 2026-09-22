package com.city.controller.activity;

import com.city.model.ActivitySessionResponse;
import com.city.service.activity.ActivitySessionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 活动卡展开后的场次/地点列表；不将场地混入第一层活动推荐。 */
@RestController
@RequestMapping("/api/v1/city/activities")
public class ActivitySessionController {
    private final ActivitySessionService activitySessionService;

    public ActivitySessionController(ActivitySessionService activitySessionService) {
        this.activitySessionService = activitySessionService;
    }

    @GetMapping("/{activityId}/sessions")
    public List<ActivitySessionResponse> sessions(@PathVariable Long activityId,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return activitySessionService.findAvailable(activityId, date);
    }
}

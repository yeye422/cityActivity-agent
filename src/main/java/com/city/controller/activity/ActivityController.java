package com.city.controller.activity;

import com.city.constants.CityConstants;
import com.city.model.ActivityRequest;
import com.city.model.ActivityResponse;
import com.city.service.activity.ActivityService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/city/activities")
public class ActivityController {
    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/personal")
    public List<ActivityResponse> findPersonal(@RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId) {
        return activityService.findPersonalActivities(userId).stream().map(ActivityResponse::from).toList();
    }

    @PostMapping("/personal")
    public ActivityResponse createPersonal(@RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId, @RequestBody ActivityRequest request) {
        return ActivityResponse.from(activityService.createPersonalActivity(userId, request));
    }

    @PutMapping("/personal/{activityId}")
    public ActivityResponse updatePersonal(@RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId, @PathVariable Long activityId, @RequestBody ActivityRequest request) {
        return ActivityResponse.from(activityService.updatePersonalActivity(userId, activityId, request));
    }

    @DeleteMapping("/personal/{activityId}")
    public void deletePersonal(@RequestHeader(value = CityConstants.USER_ID, defaultValue = "1") Long userId, @PathVariable Long activityId) {
        activityService.deletePersonalActivity(userId, activityId);
    }

    @GetMapping("/public")
    public List<ActivityResponse> findPublic() {
        return activityService.findPublicActivities().stream().map(ActivityResponse::from).toList();
    }
}

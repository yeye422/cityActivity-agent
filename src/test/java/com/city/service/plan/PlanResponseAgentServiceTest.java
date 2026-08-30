package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityResponse;
import com.city.model.RecommendResult;
import com.city.model.RecommendedActivityOption;
import com.city.model.SlotBundle;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanResponseAgentServiceTest {

    @Test
    void displayBlocksShouldUsePlannedActivityFactsAndPreserveTimeFields() {
        PlanResponseAgentService service = new PlanResponseAgentService(
                null, null, null, "qwen-max");
        SlotBundle activitySlots = slots("西安", "高新");
        SlotBundle querySlots = slots("西安", "查询条件地点");
        ActivityItem activity = new ActivityItem(
                21L,
                SourceMode.PUBLIC,
                null,
                "高新周末即兴喜剧夜",
                activitySlots,
                LocalDate.of(2026, 8, 25),
                LocalDate.of(2026, 12, 31),
                LocalTime.of(19, 30),
                LocalTime.of(21, 30),
                0.91
        );
        ActivityPlanService.PlannedActivity planned = new ActivityPlanService.PlannedActivity(
                "晚上", activity, querySlots);
        RecommendedActivityOption option = new RecommendedActivityOption(
                21L,
                SourceMode.PUBLIC,
                activity.name(),
                "适合晚上安排",
                activity.matchScore(),
                querySlots
        );

        List<ActivityResponse> blocks = ReflectionTestUtils.invokeMethod(
                service,
                "toDisplayBlocks",
                new RecommendResult(List.of(option), false),
                List.of(planned)
        );

        assertEquals(1, blocks.size());
        ActivityResponse block = blocks.getFirst();
        assertEquals(activitySlots.location(), block.location());
        assertEquals(activity.validFrom(), block.validFrom());
        assertEquals(activity.validTo(), block.validTo());
        assertEquals(activity.validStartTime(), block.validStartTime());
        assertEquals(activity.validEndTime(), block.validEndTime());
    }

    @Test
    void displayBlocksShouldIgnoreRecommendationIdsOutsidePlannedActivities() {
        PlanResponseAgentService service = new PlanResponseAgentService(
                null, null, null, "qwen-max");
        ActivityItem activity = new ActivityItem(
                21L,
                SourceMode.PUBLIC,
                null,
                "已规划活动",
                slots("西安", "高新"),
                null,
                null,
                LocalTime.of(19, 30),
                LocalTime.of(21, 30),
                0.91
        );
        ActivityPlanService.PlannedActivity planned = new ActivityPlanService.PlannedActivity(
                "晚上", activity, SlotBundle.empty());
        RecommendedActivityOption invented = new RecommendedActivityOption(
                999L,
                SourceMode.PUBLIC,
                "不存在的活动",
                "推荐理由",
                0.99,
                SlotBundle.empty()
        );

        List<ActivityResponse> blocks = ReflectionTestUtils.invokeMethod(
                service,
                "toDisplayBlocks",
                new RecommendResult(List.of(invented), false),
                List.of(planned)
        );

        assertTrue(blocks.isEmpty());
    }

    private SlotBundle slots(String city, String location) {
        return new SlotBundle(
                List.of(city),
                List.of(location),
                List.of(),
                List.of(),
                List.of(),
                List.of("演出"),
                List.of("热闹"),
                List.of("室内")
        );
    }
}

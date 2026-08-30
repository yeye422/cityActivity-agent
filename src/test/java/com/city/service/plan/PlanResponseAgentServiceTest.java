package com.city.service.plan;

import com.city.enums.SourceMode;
import com.city.model.ActivityItem;
import com.city.model.ActivityResponse;
import com.city.model.RecommendResult;
import com.city.model.RecommendedActivityOption;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import com.city.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanResponseAgentServiceTest {

    @Test
    void displayBlocksShouldUsePlannedActivityFactsAndPreserveTimeFields() {
        PlanResponseAgentService service = service();
        SlotBundle activitySlots = slots("西安", "高新");
        SlotBundle querySlots = slots("西安", "查询条件地点");
        ActivityItem activity = activity(21L, "高新周末即兴喜剧夜", activitySlots,
                LocalTime.of(19, 30), LocalTime.of(21, 30), 0.91);
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
        PlanResponseAgentService service = service();
        ActivityItem activity = activity(21L, "已规划活动", slots("西安", "高新"),
                LocalTime.of(19, 30), LocalTime.of(21, 30), 0.91);
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

    @Test
    void planPromptShouldExposeCompleteActivityFactsAndFlexibleDecisions() {
        PlanResponseAgentService service = service();
        ActivityItem activity = activity(21L, "高新周末即兴喜剧夜", slots("西安", "高新"),
                LocalTime.of(19, 30), LocalTime.of(21, 30), 0.91);
        ActivityPlanService.PlannedActivity planned = new ActivityPlanService.PlannedActivity(
                "晚上", activity, slots("西安", "高新"));

        String prompt = ReflectionTestUtils.invokeMethod(
                service,
                "buildUserPrompt",
                "帮我安排西安晚上活动",
                SourceMode.PUBLIC,
                slots("西安", "高新"),
                List.of(planned),
                WeatherRecommendationContext.indoorPriority("小雨，优先室内")
        );

        assertTrue(prompt.contains("activityId=21"));
        assertTrue(prompt.contains("slots=SlotBundle"));
        assertTrue(prompt.contains("validFrom=2026-08-25"));
        assertTrue(prompt.contains("validStartTime=19:30"));
        assertTrue(prompt.contains("matchScore=0.91"));
        assertTrue(prompt.contains("小雨，优先室内"));
        assertTrue(prompt.contains("decision=SELECT"));
        assertTrue(prompt.contains("decision=SKIP"));
        assertTrue(prompt.contains("不要求覆盖所有时段"));
    }

    @Test
    void agentShouldBeAllowedToSkipPeriodEvenWhenCandidatesExist() {
        PlanResponseAgentService service = service();
        ActivityItem afternoon = activity(11L, "下午活动", slots("西安", "钟楼"),
                LocalTime.of(14, 0), LocalTime.of(17, 0), 0.95);
        ActivityItem evening = activity(21L, "晚上活动", slots("西安", "高新"),
                LocalTime.of(19, 30), LocalTime.of(21, 30), 0.90);
        List<ActivityPlanService.PlannedActivity> plans = List.of(
                new ActivityPlanService.PlannedActivity("下午", afternoon, SlotBundle.empty(), List.of(afternoon)),
                new ActivityPlanService.PlannedActivity("晚上", evening, SlotBundle.empty(), List.of(evening))
        );

        Object parsed = ReflectionTestUtils.invokeMethod(
                service,
                "parseOutput",
                """
                        {"mealPlans":[
                          {"period":"下午","decision":"SELECT","activityId":11,"reason":"下午这个更匹配"},
                          {"period":"晚上","decision":"SKIP","reason":"晚上先留空，整体节奏更轻松"}
                        ],"speechText":"下午安排一个活动，晚上先留空。"}
                        """,
                plans,
                SlotBundle.empty()
        );

        List<ActivityPlanService.PlannedActivity> selectedPlans = ReflectionTestUtils.invokeMethod(parsed, "selectedPlans");
        assertEquals(2, selectedPlans.size());
        assertTrue(selectedPlans.get(0).matched());
        assertEquals(11L, selectedPlans.get(0).activity().id());
        assertFalse(selectedPlans.get(1).matched());
    }

    @Test
    void javaShouldReplaceTimeConflictingAgentSelectionWithLegalCandidate() {
        PlanResponseAgentService service = service();
        ActivityItem afternoon = activity(11L, "下午活动", slots("西安", "钟楼"),
                LocalTime.of(15, 0), LocalTime.of(18, 30), 0.95);
        ActivityItem eveningConflict = activity(21L, "冲突晚间活动", slots("西安", "钟楼"),
                LocalTime.of(18, 0), LocalTime.of(20, 0), 0.94);
        ActivityItem eveningLegal = activity(22L, "不冲突晚间活动", slots("西安", "钟楼"),
                LocalTime.of(19, 0), LocalTime.of(21, 0), 0.92);
        List<ActivityPlanService.PlannedActivity> plans = List.of(
                new ActivityPlanService.PlannedActivity("下午", afternoon, SlotBundle.empty(), List.of(afternoon)),
                new ActivityPlanService.PlannedActivity(
                        "晚上", eveningConflict, SlotBundle.empty(), List.of(eveningConflict, eveningLegal))
        );

        Object parsed = ReflectionTestUtils.invokeMethod(
                service,
                "parseOutput",
                """
                        {"mealPlans":[
                          {"period":"下午","decision":"SELECT","activityId":11,"reason":"下午匹配"},
                          {"period":"晚上","decision":"SELECT","activityId":21,"reason":"晚上匹配"}
                        ],"speechText":"下午和晚上各安排一个。"}
                        """,
                plans,
                SlotBundle.empty()
        );

        List<ActivityPlanService.PlannedActivity> selectedPlans = ReflectionTestUtils.invokeMethod(parsed, "selectedPlans");
        assertEquals(11L, selectedPlans.get(0).activity().id());
        assertEquals(22L, selectedPlans.get(1).activity().id());
    }

    private PlanResponseAgentService service() {
        return new PlanResponseAgentService(
                null,
                new LlmJsonService(new ObjectMapper()),
                null,
                "qwen-turbo"
        );
    }

    private ActivityItem activity(Long id,
                                  String name,
                                  SlotBundle activitySlots,
                                  LocalTime start,
                                  LocalTime end,
                                  double score) {
        return new ActivityItem(
                id,
                SourceMode.PUBLIC,
                null,
                name,
                activitySlots,
                LocalDate.of(2026, 8, 25),
                LocalDate.of(2026, 12, 31),
                start,
                end,
                score
        );
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

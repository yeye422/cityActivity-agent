package com.city.service.recommend;

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

class RecommendResponseAgentServiceTest {

    @Test
    void displayBlocksShouldUseOriginalActivityFactsAndPreserveTimeFields() {
        RecommendResponseAgentService service = new RecommendResponseAgentService(
                null, null, null, "qwen-max");
        SlotBundle activitySlots = slots("西安", "曲江");
        ActivityItem activity = new ActivityItem(
                14L,
                SourceMode.PUBLIC,
                null,
                "钟楼老城实景解谜",
                activitySlots,
                LocalDate.of(2026, 8, 25),
                LocalDate.of(2026, 12, 31),
                LocalTime.of(13, 30),
                LocalTime.of(17, 30),
                0.88
        );
        RecommendedActivityOption option = new RecommendedActivityOption(
                14L,
                SourceMode.PUBLIC,
                "被推荐语义层修改也不应成为事实来源",
                "推荐理由",
                0.88,
                SlotBundle.empty()
        );
        RecommendResult recommend = new RecommendResult(List.of(option), false);

        List<ActivityResponse> blocks = ReflectionTestUtils.invokeMethod(
                service, "toDisplayBlocks", recommend, List.of(activity));

        assertEquals(1, blocks.size());
        ActivityResponse block = blocks.getFirst();
        assertEquals(activity.name(), block.name());
        assertEquals(activitySlots.city(), block.city());
        assertEquals(activitySlots.location(), block.location());
        assertEquals(activity.validFrom(), block.validFrom());
        assertEquals(activity.validTo(), block.validTo());
        assertEquals(activity.validStartTime(), block.validStartTime());
        assertEquals(activity.validEndTime(), block.validEndTime());
    }

    @Test
    void displayBlocksShouldIgnoreRecommendationIdsOutsideCandidateSet() {
        RecommendResponseAgentService service = new RecommendResponseAgentService(
                null, null, null, "qwen-max");
        ActivityItem activity = new ActivityItem(
                14L,
                SourceMode.PUBLIC,
                null,
                "候选活动",
                slots("西安", "曲江"),
                null,
                null,
                LocalTime.of(13, 30),
                LocalTime.of(17, 30),
                0.88
        );
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
                List.of(activity)
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
                List.of("展览"),
                List.of("文艺"),
                List.of("室内")
        );
    }
}

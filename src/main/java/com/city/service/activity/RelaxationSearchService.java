package com.city.service.activity;

import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivityRankResult;
import com.city.model.ActivitySearchRequest;
import com.city.model.RelaxationOption;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 严格条件无结果时的候选回退策略。
 * 城市、区域、时间、预算、活动类型以及所有显式排除条件保持不变；只逐层放宽正向偏好槽位。
 */
@Service
public class RelaxationSearchService {
    private final ActivitySearchService activitySearchService;
    private final ActivityRankService activityRankService;

    public RelaxationSearchService(ActivitySearchService activitySearchService,
                                   ActivityRankService activityRankService) {
        this.activitySearchService = activitySearchService;
        this.activityRankService = activityRankService;
    }

    public List<RelaxationOption> options(SourceMode sourceMode,
                                          Long userId,
                                          SlotBundle originalSlots,
                                          SlotBundle excludedSlots,
                                          List<Long> excludeActivityIds,
                                          TimeConstraint timeConstraint) {
        return List.of(1, 2).stream()
                .map(level -> find(sourceMode, userId, originalSlots, excludedSlots,
                        excludeActivityIds, timeConstraint, level))
                .filter(result -> !result.ranked().isEmpty())
                .map(result -> new RelaxationOption(
                        result.level(), result.label(), result.relaxedSlots(), result.ranked().size()))
                .toList();
    }

    public SearchResult find(SourceMode sourceMode,
                             Long userId,
                             SlotBundle originalSlots,
                             SlotBundle excludedSlots,
                             List<Long> excludeActivityIds,
                             TimeConstraint timeConstraint,
                             Integer level) {
        if (level == null || level < 1 || level > 2) {
            throw new CityException("无效的相近活动方案");
        }
        SlotBundle original = originalSlots == null ? SlotBundle.empty() : originalSlots;
        SlotBundle excluded = excludedSlots == null ? SlotBundle.empty() : excludedSlots;
        SlotBundle query = queryFor(original, level);
        List<ActivityItem> candidates = activitySearchService.search(
                new ActivitySearchRequest(
                        sourceMode, userId, query, excludeActivityIds, timeConstraint, excluded));
        ActivityRankResult rankResult = activityRankService.rank(
                new ActivityRankRequest(candidates, original, timeConstraint, excludeActivityIds));
        return new SearchResult(level, labelFor(level), relaxedSlotsFor(level), query, rankResult.ranked());
    }

    /**
     * feature 默认作为客观偏好保留，不因普通 Relaxation 被悄悄丢掉；
     * level1 放宽体验目标/风格，level2 再放宽 companion/duration。
     */
    private SlotBundle queryFor(SlotBundle slots, int level) {
        boolean broad = level >= 2;
        return new SlotBundle(
                slots.city(),
                slots.location(),
                List.of(),
                broad ? List.of() : slots.companion(),
                slots.budget(),
                slots.activityType(),
                List.of(),
                broad ? List.of() : slots.duration(),
                slots.feature()
        );
    }

    private String labelFor(int level) {
        return level == 1 ? "仅放宽体验目标和风格" : "进一步放宽同行关系和活动时长";
    }

    private List<String> relaxedSlotsFor(int level) {
        return level == 1
                ? List.of("experienceGoal", "style")
                : List.of("experienceGoal", "style", "companion", "duration");
    }

    public record SearchResult(int level, String label, List<String> relaxedSlots,
                               SlotBundle querySlots, List<ActivityItem> ranked) {
    }
}

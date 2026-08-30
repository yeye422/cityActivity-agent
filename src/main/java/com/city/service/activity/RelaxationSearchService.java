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

    /**
     * 放宽偏好时仍保留用户指定的日期/时段和 excludedSlots。
     * negative constraint 永远不能因为“相近活动”而被悄悄放宽。
     */
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
        // 用原始正向条件和原始时间评分，使最接近原需求的活动排在前面；负向条件已在 Search 阶段严格排除。
        ActivityRankResult rankResult = activityRankService.rank(
                new ActivityRankRequest(candidates, original, timeConstraint, excludeActivityIds));
        return new SearchResult(level, labelFor(level), relaxedSlotsFor(level), query, rankResult.ranked());
    }

    private SlotBundle queryFor(SlotBundle slots, int level) {
        boolean broad = level >= 2;
        return new SlotBundle(
                slots.city(), slots.location(),
                List.of(),
                broad ? List.of() : slots.scene(),
                slots.budget(), slots.activityType(),
                List.of(),
                broad ? List.of() : slots.duration()
        );
    }

    private String labelFor(int level) {
        return level == 1 ? "仅放宽氛围和风格" : "进一步放宽同行场景和时长";
    }

    private List<String> relaxedSlotsFor(int level) {
        return level == 1 ? List.of("mood", "style") : List.of("mood", "style", "scene", "duration");
    }

    public record SearchResult(int level, String label, List<String> relaxedSlots,
                               SlotBundle querySlots, List<ActivityItem> ranked) {
    }
}

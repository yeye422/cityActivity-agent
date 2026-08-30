package com.city.service.activity;

import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.ActivitySearchRequest;
import com.city.model.RelaxationOption;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 严格条件无结果时的候选回退策略。
 * 城市、区域、时间、预算和活动类型保持不变；只逐层放宽偏好类槽位。
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

    public List<RelaxationOption> options(SourceMode sourceMode, Long userId, SlotBundle originalSlots, List<Long> excludeActivityIds) {
        return options(sourceMode, userId, originalSlots, excludeActivityIds, TimeConstraint.empty());
    }

    /** 放宽偏好时仍必须保留用户指定的日期，不能把“周六”悄悄放宽掉。 */
    public List<RelaxationOption> options(SourceMode sourceMode, Long userId, SlotBundle originalSlots,
                                          List<Long> excludeActivityIds, TimeConstraint timeConstraint) {
        return List.of(1, 2).stream()
                .map(level -> find(sourceMode, userId, originalSlots, excludeActivityIds, timeConstraint, level))
                .filter(result -> !result.ranked().isEmpty())
                .map(result -> new RelaxationOption(result.level(), result.label(), result.relaxedSlots(), result.ranked().size()))
                .toList();
    }

    public SearchResult find(SourceMode sourceMode, Long userId, SlotBundle originalSlots,
                             List<Long> excludeActivityIds, Integer level) {
        return find(sourceMode, userId, originalSlots, excludeActivityIds, TimeConstraint.empty(), level);
    }

    public SearchResult find(SourceMode sourceMode, Long userId, SlotBundle originalSlots,
                             List<Long> excludeActivityIds, TimeConstraint timeConstraint, Integer level) {
        if (level == null || level < 1 || level > 2) {
            throw new CityException("无效的相近活动方案");
        }
        SlotBundle original = originalSlots == null ? SlotBundle.empty() : originalSlots;
        SlotBundle query = queryFor(original, level);
        List<ActivityItem> candidates = activitySearchService.search(
                new ActivitySearchRequest(sourceMode, userId, query, excludeActivityIds, timeConstraint));
        // 用原始条件评分，使最接近原需求的活动排在前面。
        List<ActivityItem> ranked = activityRankService.rank(
                new ActivityRankRequest(candidates, original, excludeActivityIds));
        return new SearchResult(level, labelFor(level), relaxedSlotsFor(level), query, ranked);
    }

    private SlotBundle queryFor(SlotBundle slots, int level) {
        boolean broad = level >= 2;
        return new SlotBundle(
                slots.city(), slots.location(),
                List.of(),                                  // level 1 起放宽氛围
                broad ? List.of() : slots.scene(),
                slots.budget(), slots.activityType(),
                List.of(),                                  // level 1 起放宽风格
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

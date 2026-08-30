package com.city.service.activity;

import com.city.model.ActivityItem;
import com.city.model.ActivityRankRequest;
import com.city.model.SlotBundle;
import com.city.model.WeatherRecommendationContext;
import org.springframework.stereotype.Service;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 活动重排服务（Orchestrator 推荐流水线第二层）。
 * 消费 slots、excludeActivityIds，对检索候选二次打分排序。
 */
@Service
public class ActivityRankService {

    /**
     * 在search检索时,只要用户输入的槽位信息有匹配有交集就会返回
     * 但是rank排序,是比较用户输入的槽位信息是否更大程度的得到满足
     * 执行重排并返回最多 10 个候选。
     * 由 Orchestrator#completeRecommendation 在 ACTIVITY_SEARCHED 之后调用。
     */
    public List<ActivityItem> rank(ActivityRankRequest request) {
        return rank(request, WeatherRecommendationContext.inactive());
    }

    /** 天气只影响排序，不会把活动从结果集中剔除。 */
    public List<ActivityItem> rank(ActivityRankRequest request, WeatherRecommendationContext weather) {
        // 将 excludeActivityIds 转为 HashSet，便于 O(1) 查找
        Set<Long> excludeIds = new HashSet<>(request.excludeActivityIds() == null ? List.of() : request.excludeActivityIds());
        return request.candidates().stream()
                .filter(item -> item != null && !excludeIds.contains(item.id()))  // 过滤 null 和需排除的 ID
                .map(item -> withRankScore(item, request.slots(), weather)) // 计算排序分数
                .sorted(Comparator.comparingDouble((ActivityItem item) -> item.matchScore()).reversed()) // 按分数降序
                .limit(10)   // 最多返回 10 条
                .toList();
    }

    /**
     * 计算重排后的归一化分数并返回新 ActivityItem（matchScore 替换为 finalScore）。
     */
    private ActivityItem withRankScore(ActivityItem item, SlotBundle query, WeatherRecommendationContext weather) {
        double slotScore = slotScore(item.slots(), query);           // 槽位命中分 [0,1]
        double finalScore = weatherScore(slotScore, item.slots(), weather);
        return new ActivityItem(item.id(), item.sourceType(), item.ownerUserId(), item.name(), item.slots(),
                item.validFrom(), item.validTo(), item.validStartTime(), item.validEndTime(), finalScore);
    }

    private double weatherScore(double slotScore, SlotBundle item, WeatherRecommendationContext weather) {
        if (weather == null || !weather.active()) return slotScore;
        Set<String> durations = Set.copyOf(item.duration() == null ? List.of() : item.duration());
        if (durations.contains("室内")) return clamp(slotScore * 0.88 + 0.12);
        if (durations.contains("户外") || durations.contains("室外")) return clamp(slotScore * 0.82);
        return slotScore;
    }

    /** 计算活动 slots 与查询 slots 的 9 维平均重叠比例。 */
    private double slotScore(SlotBundle item, SlotBundle query) {
        SlotBundle safeQuery = query == null ? SlotBundle.empty() : query;
        double total = 0.0;
        int dimensions = 0;
        ScorePart[] parts = {
                scorePart(item.city(), safeQuery.city()),
                scorePart(item.location(), safeQuery.location()),
                scorePart(item.mood(), safeQuery.mood()),
                scorePart(item.scene(), safeQuery.scene()),
                scorePart(item.budget(), safeQuery.budget()),
                scorePart(item.activityType(), safeQuery.activityType()),
                scorePart(item.style(), safeQuery.style()),
                scorePart(item.duration(), safeQuery.duration())
        };
        for (ScorePart part : parts) {
            if (part.active()) {
                total += part.score();
                dimensions++;
            }
        }
        return dimensions == 0 ? 0 : clamp(total / dimensions);
    }

    private ScorePart scorePart(List<String> itemValues, List<String> queryValues) {
        return new ScorePart(queryValues != null && !queryValues.isEmpty(), overlap(itemValues, queryValues));
    }

    /** 计算 queryValues 中有多少标签出现在 itemValues 中，返回命中比例。 */
    private double overlap(List<String> itemValues, List<String> queryValues) {
        if (queryValues == null || queryValues.isEmpty()) {
            return 0; // 查询侧该维度为空时不计分
        }
        Set<String> itemSet = Set.copyOf(itemValues == null ? List.of() : itemValues);
        long hits = queryValues.stream().filter(itemSet::contains).count();
        // hits * 1.0 / queryValues.size()  即  命中的用户标签数 / 用户查询标签总数
        // 例如 鸡胸肉的health_Goal有[清淡，高蛋白] 猪肘的health_Goal有[高蛋白]，用户的输入的health_Goal是[清淡，高蛋白]
        // 那这里 queryValues就是[清淡，高蛋白]
        // 鸡胸肉的 hits = 2   猪肘的 hits = 1
        // 于是最终得分，鸡胸肉的 score = 2 / 2 = 1,  猪肘的 score = 1 / 2 = 0.5 分
        // 排序的规则就是看 谁更能满足用户的需求
        return hits * 1.0 / queryValues.size();
    }

    /** 将分数约束在 [0, 1] 区间。 */
    private double clamp(double score) {
        return Math.max(0, Math.min(1, score));
    }

    private record ScorePart(boolean active, double score) {
    }
}

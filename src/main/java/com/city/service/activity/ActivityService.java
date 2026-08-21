package com.city.service.activity;

import com.city.exception.CityException;
import com.city.mapper.ActivityMapper;
import com.city.model.ActivityItem;
import com.city.model.ActivityItemRow;
import com.city.model.ActivityRequest;
import com.city.model.SlotBundle;
import com.city.enums.SourceMode;
import com.city.service.slot.SlotOptionService;
import com.city.util.JsonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 活动数据服务。
 * 提供 CRUD 和基于 MySQL JSON_OVERLAPS 的标签检索；Orchestrator 推荐链路通过 {@link #search} 召回候选。
 */
@Service
public class ActivityService {

    /** 单次检索从 DB 拉取的最大行数，初排后取 top10 交给 Rank 层。 */
    private static final int SEARCH_LIMIT = 50;
    private final ActivityMapper activityMapper;
    private final SlotOptionService slotOptionService;
    private final JsonService jsonService;
    public ActivityService(ActivityMapper activityMapper, SlotOptionService slotOptionService, JsonService jsonService) {
        this.activityMapper = activityMapper;
        this.slotOptionService = slotOptionService;
        this.jsonService = jsonService;
    }

    public List<ActivityItem> findPersonalActivities(Long userId) {
        return activityMapper.findPersonalActivities(userId).stream().map(this::toActivityItem).toList();
    }

    public List<ActivityItem> findPublicActivities() {
        return activityMapper.findPublicActivities().stream().map(this::toActivityItem).toList();
    }

    /**
     * PERSONAL 模式空库前置检查。
     * 由 Orchestrator#handleTurn 调用，count > 0 才继续推荐链路。
     */
    public boolean hasPersonalActivities(Long userId) {
        return activityMapper.countPersonalActivities(userId) > 0; // 查个人活动数量是否大于 0
    }

    @Transactional
    public ActivityItem createPersonalActivity(Long userId, ActivityRequest request) {
        validateActivityRequest(request);
        ActivityItemRow row = toRow(null, SourceMode.PERSONAL, userId, request);
        activityMapper.insert(row);
        return toActivityItem(row);
    }

    @Transactional
    public ActivityItem updatePersonalActivity(Long userId, Long activityId, ActivityRequest request) {
        validateActivityRequest(request);
        ActivityItemRow row = toRow(activityId, SourceMode.PERSONAL, userId, request);
        int updated = activityMapper.updatePersonal(row);
        if (updated == 0) {
            throw new CityException("个人活动不存在或无权限修改");
        }
        return toActivityItem(activityMapper.findPersonalById(activityId, userId));
    }

    @Transactional
    public void deletePersonalActivity(Long userId, Long activityId) {
        int deleted = activityMapper.deletePersonal(activityId, userId);
        if (deleted == 0) {
            throw new CityException("个人活动不存在或无权限删除");
        }
    }

    /**
     * 按槽位标签检索活动并计算初排 matchScore。
     * 由 ActivitySearchService#search 调用；MySQL JSON_OVERLAPS 召回后 Java 侧 overlap 打分。
     */
    public List<ActivityItem> search(SourceMode sourceMode, Long userId, SlotBundle slots) {
        // MyBatis 执行 JSON_OVERLAPS 检索，9 维槽位各传 JSON 数组，最多拉 SEARCH_LIMIT=50 条
        List<ActivityItemRow> rows = activityMapper.search(
                sourceMode,                                      // PERSONAL 或 PUBLIC，决定查哪张数据
                userId,                                          // PERSONAL 时过滤 owner_user_id
                jsonService.toJsonArray(slots.city()),           // 城市标签 JSON 数组
                jsonService.toJsonArray(slots.location()),       // 位置/区域标签 JSON 数组
                jsonService.toJsonArray(slots.activityTime()),       // 活动时间标签 JSON 数组
                jsonService.toJsonArray(slots.mood()),           // 心情标签 JSON 数组
                jsonService.toJsonArray(slots.scene()),          // 场景标签 JSON 数组
                jsonService.toJsonArray(slots.budget()),     // 预算标签 JSON 数组
                jsonService.toJsonArray(slots.activityType()),        // 菜系 JSON 数组
                jsonService.toJsonArray(slots.style()),          // 活动风格 JSON 数组
                jsonService.toJsonArray(slots.duration()),    // 活动时长 JSON 数组
                SEARCH_LIMIT                                     // DB 层最多返回 50 行
        );
        // Row → ActivityItem
        return rows.stream().map(this::toActivityItem).toList();
    }

    private void validateActivityRequest(ActivityRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new CityException("活动名称不能为空");
        }
        SlotBundle slots = request.toSlots();
        if (slots.activityTime().isEmpty()) {
            throw new CityException("活动时间至少选择一个标签");
        }
        slotOptionService.validate(slots);
    }

    private ActivityItemRow toRow(Long id, SourceMode sourceMode, Long ownerUserId, ActivityRequest request) {
        SlotBundle slots = request.toSlots();
        ActivityItemRow row = new ActivityItemRow();
        row.setId(id);
        row.setSourceType(sourceMode.name());
        row.setOwnerUserId(ownerUserId);
        row.setName(request.name().trim());
        row.setCity(jsonService.toJsonArray(slots.city()));
        row.setLocation(jsonService.toJsonArray(slots.location()));
        row.setActivityTime(jsonService.toJsonArray(slots.activityTime()));
        row.setMood(jsonService.toJsonArray(slots.mood()));
        row.setScene(jsonService.toJsonArray(slots.scene()));
        row.setBudget(jsonService.toJsonArray(slots.budget()));
        row.setActivityType(jsonService.toJsonArray(slots.activityType()));
        row.setStyle(jsonService.toJsonArray(slots.style()));
        row.setDuration(jsonService.toJsonArray(slots.duration()));
        return row;
    }

    private ActivityItem toActivityItem(ActivityItemRow row) {
        if (row == null) {
            return null;
        }
        SlotBundle slots = new SlotBundle(
                jsonService.fromJsonArray(row.getCity()),
                jsonService.fromJsonArray(row.getLocation()),
                jsonService.fromJsonArray(row.getActivityTime()),
                jsonService.fromJsonArray(row.getMood()),
                jsonService.fromJsonArray(row.getScene()),
                jsonService.fromJsonArray(row.getBudget()),
                jsonService.fromJsonArray(row.getActivityType()),
                jsonService.fromJsonArray(row.getStyle()),
                jsonService.fromJsonArray(row.getDuration())
        );
        return new ActivityItem(
                row.getId(),
                SourceMode.valueOf(row.getSourceType()),
                row.getOwnerUserId(),
                row.getName(),
                slots,
                0
        );
    }
}

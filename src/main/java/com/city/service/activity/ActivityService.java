package com.city.service.activity;

import com.city.enums.SourceMode;
import com.city.exception.CityException;
import com.city.mapper.ActivityMapper;
import com.city.model.ActivityItem;
import com.city.model.ActivityItemRow;
import com.city.model.ActivityRequest;
import com.city.model.SlotBundle;
import com.city.model.TimeConstraint;
import com.city.service.slot.SlotOptionService;
import com.city.util.JsonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 活动数据服务。
 * 提供 CRUD 和基于 MySQL JSON_OVERLAPS 的标签检索；Orchestrator 推荐链路通过 {@link #search} 召回候选。
 */
@Service
public class ActivityService {

    /** 单次检索从 DB 拉取的最大行数，初排后取 top10 交给 Rank 层。 */
    private static final int SEARCH_LIMIT = 50;
    private static final List<String> BUDGET_ORDER = List.of("免费", "100元内", "200元内", "300元内");

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

    public boolean hasPersonalActivities(Long userId) {
        return activityMapper.countPersonalActivities(userId) > 0;
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

    public List<ActivityItem> search(SourceMode sourceMode, Long userId, SlotBundle slots) {
        return search(sourceMode, userId, slots, TimeConstraint.empty());
    }

    public List<ActivityItem> search(SourceMode sourceMode, Long userId, SlotBundle slots, TimeConstraint timeConstraint) {
        return search(sourceMode, userId, slots, timeConstraint, SlotBundle.empty());
    }

    public List<ActivityItem> search(SourceMode sourceMode, Long userId, SlotBundle slots,
                                     TimeConstraint timeConstraint, SlotBundle excludedSlots) {
        SlotBundle safeSlots = slots == null ? SlotBundle.empty() : slots;
        SlotBundle safeExcluded = excludedSlots == null ? SlotBundle.empty() : excludedSlots;
        List<String> searchableBudgets = expandBudgetUpperBound(safeSlots.budget());

        List<ActivityItemRow> rows = activityMapper.search(
                sourceMode,
                userId,
                jsonService.toJsonArray(safeSlots.city()),
                jsonService.toJsonArray(safeSlots.location()),
                jsonService.toJsonArray(safeSlots.experienceGoal()),
                jsonService.toJsonArray(safeSlots.companion()),
                jsonService.toJsonArray(searchableBudgets),
                jsonService.toJsonArray(safeSlots.activityType()),
                jsonService.toJsonArray(safeSlots.style()),
                jsonService.toJsonArray(safeSlots.duration()),
                jsonService.toJsonArray(safeSlots.feature()),
                timeConstraint != null && timeConstraint.hasDate() ? timeConstraint.dateStart() : null,
                timeConstraint != null && timeConstraint.hasDate() ? timeConstraint.dateEnd() : null,
                timeConstraint != null && timeConstraint.hasTime() ? timeConstraint.startTime() : null,
                timeConstraint != null && timeConstraint.hasTime() ? timeConstraint.endTime() : null,
                jsonService.toJsonArray(safeExcluded.city()),
                jsonService.toJsonArray(safeExcluded.location()),
                jsonService.toJsonArray(safeExcluded.experienceGoal()),
                jsonService.toJsonArray(safeExcluded.companion()),
                jsonService.toJsonArray(safeExcluded.budget()),
                jsonService.toJsonArray(safeExcluded.activityType()),
                jsonService.toJsonArray(safeExcluded.style()),
                jsonService.toJsonArray(safeExcluded.duration()),
                jsonService.toJsonArray(safeExcluded.feature()),
                SEARCH_LIMIT
        );
        return rows.stream().map(this::toActivityItem).toList();
    }

    private List<String> expandBudgetUpperBound(List<String> budgets) {
        if (budgets == null || budgets.isEmpty()) {
            return List.of();
        }
        int maxIndex = -1;
        for (String budget : budgets) {
            maxIndex = Math.max(maxIndex, BUDGET_ORDER.indexOf(budget));
        }
        if (maxIndex < 0) {
            return budgets;
        }
        return List.copyOf(BUDGET_ORDER.subList(0, maxIndex + 1));
    }

    private void validateActivityRequest(ActivityRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new CityException("活动名称不能为空");
        }
        SlotBundle slots = request.toSlots();
        if (request.durationMinutes() != null && (request.durationMinutes() <= 0 || request.durationMinutes() > 24 * 60)) {
            throw new CityException("活动预计耗时必须在 1~1440 分钟之间");
        }
        if ((request.validFrom() == null) != (request.validTo() == null)) {
            throw new CityException("有效日期请同时填写开始和结束日期");
        }
        if (request.validFrom() != null && request.validFrom().isAfter(request.validTo())) {
            throw new CityException("有效结束日期不能早于开始日期");
        }
        if ((request.validStartTime() == null) != (request.validEndTime() == null)) {
            throw new CityException("每日有效时段请同时填写开始和结束时间");
        }
        if (request.validStartTime() != null && !request.validStartTime().isBefore(request.validEndTime())) {
            throw new CityException("每日结束时间必须晚于开始时间");
        }
        if (request.description() != null && request.description().trim().length() > 2000) {
            throw new CityException("活动描述不能超过 2000 个字符");
        }
        slotOptionService.validate(slots);
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) return null;
        return description.trim();
    }

    private ActivityItemRow toRow(Long id, SourceMode sourceMode, Long ownerUserId, ActivityRequest request) {
        SlotBundle slots = request.toSlots();
        ActivityItemRow row = new ActivityItemRow();
        row.setId(id);
        row.setSourceType(sourceMode.name());
        row.setOwnerUserId(ownerUserId);
        row.setName(request.name().trim());
        row.setDescription(normalizeDescription(request.description()));
        row.setCity(jsonService.toJsonArray(slots.city()));
        row.setLocation(jsonService.toJsonArray(slots.location()));
        row.setExperienceGoal(jsonService.toJsonArray(slots.experienceGoal()));
        row.setCompanion(jsonService.toJsonArray(slots.companion()));
        row.setBudget(jsonService.toJsonArray(slots.budget()));
        row.setActivityType(jsonService.toJsonArray(slots.activityType()));
        row.setStyle(jsonService.toJsonArray(slots.style()));
        row.setDuration(jsonService.toJsonArray(slots.duration()));
        row.setFeature(jsonService.toJsonArray(slots.feature()));
        row.setDurationMinutes(request.durationMinutes());
        row.setValidFrom(request.validFrom());
        row.setValidTo(request.validTo());
        row.setValidStartTime(request.validStartTime());
        row.setValidEndTime(request.validEndTime());
        return row;
    }

    private ActivityItem toActivityItem(ActivityItemRow row) {
        if (row == null) {
            return null;
        }
        SlotBundle slots = new SlotBundle(
                jsonService.fromJsonArray(row.getCity()),
                jsonService.fromJsonArray(row.getLocation()),
                jsonService.fromJsonArray(row.getExperienceGoal()),
                jsonService.fromJsonArray(row.getCompanion()),
                jsonService.fromJsonArray(row.getBudget()),
                jsonService.fromJsonArray(row.getActivityType()),
                jsonService.fromJsonArray(row.getStyle()),
                jsonService.fromJsonArray(row.getDuration()),
                jsonService.fromJsonArray(row.getFeature())
        );
        return new ActivityItem(
                row.getId(),
                SourceMode.valueOf(row.getSourceType()),
                row.getOwnerUserId(),
                row.getName(),
                row.getDescription(),
                slots,
                row.getValidFrom(),
                row.getValidTo(),
                row.getValidStartTime(),
                row.getValidEndTime(),
                row.getDurationMinutes(),
                0
        );
    }
}

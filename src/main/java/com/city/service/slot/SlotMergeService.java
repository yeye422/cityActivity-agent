package com.city.service.slot;

import com.city.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 槽位合并服务，用于多轮对话中合并历史槽位与本轮新识别的槽位。
 * <p>
 * 合并策略：本轮非空值与历史值取并集，本轮为空则保留历史值。
 */
@Service
public class SlotMergeService {

    /**
     * 合并历史槽位与新槽位。
     * <p>
     * 规则：
     * - 如果新槽位的某个字段非空（非 null 且非空列表），则与历史值合并并去重
     * - 如果新槽位的某个字段为空，则保留历史值
     * - 如果历史槽位为 null，则使用新槽位
     *
     * @param historicalSlots 历史槽位（来自会话状态）
     * @param newSlots        新槽位（本轮意图识别结果）
     * @return 合并后的槽位
     */
    public SlotBundle merge(SlotBundle historicalSlots, SlotBundle newSlots) {
        // 如果历史槽位为空，直接返回新槽位
        if (historicalSlots == null) {
            return newSlots != null ? newSlots : SlotBundle.empty();
        }

        // 如果新槽位为空，直接返回历史槽位
        if (newSlots == null) {
            return historicalSlots;
        }

        // 逐字段合并：新值非空则覆盖，否则保留历史值
        return new SlotBundle(
                mergeList(historicalSlots.city(), newSlots.city()),
                mergeList(historicalSlots.location(), newSlots.location()),
                mergeList(historicalSlots.experienceGoal(), newSlots.experienceGoal()),
                mergeList(historicalSlots.companion(), newSlots.companion()),
                mergeList(historicalSlots.budget(), newSlots.budget()),
                mergeList(historicalSlots.activityType(), newSlots.activityType()),
                mergeList(historicalSlots.style(), newSlots.style()),
                mergeList(historicalSlots.duration(), newSlots.duration())
        );
    }

    /** 合并列表字段，默认将多轮补充条件视为并集；显式 SET 由 SlotMutationService 负责覆盖。 */
    private List<String> mergeList(List<String> historical, List<String> newList) {
        List<String> oldValues = historical == null ? List.of() : historical;
        if (newList == null || newList.isEmpty()) return new ArrayList<>(oldValues);
        return java.util.stream.Stream.concat(oldValues.stream(), newList.stream()).distinct().toList();
    }
}

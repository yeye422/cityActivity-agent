package com.city.service.slot;

import com.city.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 槽位合并服务，用于多轮对话中合并历史槽位与本轮新识别的槽位。
 * 本轮非空值与历史值取并集；显式 SET/CLEAR/REMOVE 由 SlotMutationService 负责。
 */
@Service
public class SlotMergeService {

    public SlotBundle merge(SlotBundle historicalSlots, SlotBundle newSlots) {
        if (historicalSlots == null) {
            return newSlots != null ? newSlots : SlotBundle.empty();
        }
        if (newSlots == null) {
            return historicalSlots;
        }

        return new SlotBundle(
                mergeList(historicalSlots.city(), newSlots.city()),
                mergeList(historicalSlots.location(), newSlots.location()),
                mergeList(historicalSlots.experienceGoal(), newSlots.experienceGoal()),
                mergeList(historicalSlots.companion(), newSlots.companion()),
                mergeList(historicalSlots.budget(), newSlots.budget()),
                mergeList(historicalSlots.activityType(), newSlots.activityType()),
                mergeList(historicalSlots.style(), newSlots.style()),
                mergeList(historicalSlots.duration(), newSlots.duration()),
                mergeList(historicalSlots.feature(), newSlots.feature())
        );
    }

    private List<String> mergeList(List<String> historical, List<String> newList) {
        List<String> oldValues = historical == null ? List.of() : historical;
        if (newList == null || newList.isEmpty()) return new ArrayList<>(oldValues);
        return java.util.stream.Stream.concat(oldValues.stream(), newList.stream()).distinct().toList();
    }
}

package com.city.service.retrieval;

import com.city.model.ActivityItem;
import com.city.model.SlotBundle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 构造 Pinecone FTS 与 Dense Vector 共用的活动语义文档。 */
@Component
public class ActivitySemanticDocumentBuilder {

    public String build(ActivityItem item) {
        if (item == null) return "";
        SlotBundle slots = item.slots() == null ? SlotBundle.empty() : item.slots();
        List<String> sections = new ArrayList<>();
        sections.add("活动：" + safe(item.name()));
        if (item.description() != null && !item.description().isBlank()) {
            sections.add("描述：" + item.description().trim());
        }
        append(sections, "城市", slots.city());
        append(sections, "地点", slots.location());
        append(sections, "体验目标", slots.experienceGoal());
        append(sections, "同行", slots.companion());
        append(sections, "预算", slots.budget());
        append(sections, "类型", slots.activityType());
        append(sections, "风格", slots.style());
        append(sections, "时长", slots.duration());
        append(sections, "特征", slots.feature());
        return String.join("；", sections);
    }

    private void append(List<String> sections, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            sections.add(label + "：" + String.join("、", values));
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

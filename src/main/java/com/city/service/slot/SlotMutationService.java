package com.city.service.slot;

import com.city.enums.ConstraintOperationType;
import com.city.model.ConstraintOperation;
import com.city.model.SlotBundle;
import com.city.model.SlotMutation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通路径只执行 IntentAgent 的结构化 operations；
 * “不要/不限”等原文关键词解析仅保留给模型失败后的 fallback 路径。
 */
@Service
public class SlotMutationService {
    private final SlotOptionService options;

    public SlotMutationService(SlotOptionService options) {
        this.options = options;
    }

    /**
     * 模型失败后的关键词 fallback：从用户原文补齐明确的 CLEAR / REMOVE 语义。
     * 正常模型成功路径不要调用此方法。
     */
    public SlotMutation apply(String input,
                              SlotBundle current,
                              SlotBundle currentExcluded,
                              Set<String> currentUnconstrained) {
        String text = input == null ? "" : input.replaceAll("\\s+", "");
        SlotBundle included = current == null ? SlotBundle.empty() : current;
        SlotBundle excluded = currentExcluded == null ? SlotBundle.empty() : currentExcluded;
        Set<String> unconstrained = mutableSet(currentUnconstrained);
        Map<String, List<String>> dictionary = options.findAllOptions();
        for (String field : SlotOptionService.SLOT_NAMES) {
            if (clears(text, field)) {
                included = replace(included, field, List.of());
                excluded = replace(excluded, field, List.of());
                unconstrained.add(field);
                continue;
            }
            for (String value : dictionary.getOrDefault(field, List.of())) {
                if (text.contains("不要" + value)
                        || text.contains("不想" + value)
                        || text.contains("不想看" + value)
                        || text.contains("别" + value)) {
                    included = replace(included, field, without(values(included, field), value));
                    excluded = replace(excluded, field, append(values(excluded, field), value));
                    unconstrained.remove(field);
                }
            }
        }
        return new SlotMutation(included, excluded, unconstrained);
    }

    /**
     * 正常模型成功路径：只执行已经过字典过滤的结构化 operations，
     * 不再扫描用户原文做第二次语义纠正。input 参数仅为兼容现有调用签名保留。
     */
    public SlotMutation apply(List<ConstraintOperation> operations,
                              String input,
                              SlotBundle current,
                              SlotBundle currentExcluded,
                              Set<String> currentUnconstrained) {
        SlotBundle included = current == null ? SlotBundle.empty() : current;
        SlotBundle excluded = currentExcluded == null ? SlotBundle.empty() : currentExcluded;
        Set<String> unconstrained = mutableSet(currentUnconstrained);
        if (operations != null) {
            Map<String, List<ConstraintOperation>> grouped = new LinkedHashMap<>();
            for (ConstraintOperation operation : operations) {
                if (operation == null || operation.field() == null || operation.op() == null
                        || !SlotOptionService.SLOT_NAMES.contains(operation.field())) continue;
                grouped.computeIfAbsent(operation.field(), ignored -> new ArrayList<>()).add(operation);
            }
            for (Map.Entry<String, List<ConstraintOperation>> entry : grouped.entrySet()) {
                String field = entry.getKey();
                List<ConstraintOperation> fieldOperations = entry.getValue();
                if (fieldOperations.stream().anyMatch(operation -> operation.op() == ConstraintOperationType.CLEAR)) {
                    included = replace(included, field, List.of());
                    excluded = replace(excluded, field, List.of());
                    unconstrained.add(field);
                    continue;
                }
                ConstraintOperation set = fieldOperations.stream()
                        .filter(operation -> operation.op() == ConstraintOperationType.SET)
                        .reduce((first, last) -> last).orElse(null);
                if (set != null) {
                    List<String> values = set.values() == null ? List.of() : set.values();
                    included = replace(included, field, values);
                    excluded = replace(excluded, field, withoutAny(values(excluded, field), values));
                    unconstrained.remove(field);
                    continue;
                }
                for (ConstraintOperation operation : fieldOperations) {
                    List<String> values = operation.values() == null ? List.of() : operation.values();
                    if (operation.op() == ConstraintOperationType.ADD) {
                        included = replace(included, field, appendAll(values(included, field), values));
                        excluded = replace(excluded, field, withoutAny(values(excluded, field), values));
                        unconstrained.remove(field);
                    } else if (operation.op() == ConstraintOperationType.REMOVE) {
                        included = replace(included, field, withoutAny(values(included, field), values));
                        excluded = replace(excluded, field, appendAll(values(excluded, field), values));
                        unconstrained.remove(field);
                    }
                }
            }
        }
        return new SlotMutation(included, excluded, unconstrained);
    }

    private Set<String> mutableSet(Set<String> source) {
        return new LinkedHashSet<>(source == null ? Set.of() : source);
    }

    private boolean clears(String text, String field) {
        return switch (field) {
            case "city" -> text.contains("城市不限") || text.contains("地点不限");
            case "budget" -> text.contains("预算不限") || text.contains("不限制预算");
            case "style" -> text.contains("风格不限");
            case "activityType" -> text.contains("类型不限") || text.contains("活动不限");
            default -> false;
        };
    }

    private List<String> values(SlotBundle slots, String field) {
        return switch (field) {
            case "city" -> slots.city();
            case "location" -> slots.location();
            case "mood" -> slots.mood();
            case "scene" -> slots.scene();
            case "budget" -> slots.budget();
            case "activityType" -> slots.activityType();
            case "style" -> slots.style();
            default -> slots.duration();
        };
    }

    private SlotBundle replace(SlotBundle s, String f, List<String> v) {
        return new SlotBundle(
                f.equals("city") ? v : s.city(), f.equals("location") ? v : s.location(),
                f.equals("mood") ? v : s.mood(), f.equals("scene") ? v : s.scene(),
                f.equals("budget") ? v : s.budget(), f.equals("activityType") ? v : s.activityType(),
                f.equals("style") ? v : s.style(), f.equals("duration") ? v : s.duration());
    }

    private List<String> without(List<String> source, String value) {
        return source.stream().filter(v -> !v.equals(value)).toList();
    }

    private List<String> append(List<String> source, String value) {
        return java.util.stream.Stream.concat(source.stream(), java.util.stream.Stream.of(value)).distinct().toList();
    }

    private List<String> appendAll(List<String> source, List<String> values) {
        return java.util.stream.Stream.concat(source.stream(), values.stream()).distinct().toList();
    }

    private List<String> withoutAny(List<String> source, List<String> values) {
        return source.stream().filter(v -> !values.contains(v)).toList();
    }
}

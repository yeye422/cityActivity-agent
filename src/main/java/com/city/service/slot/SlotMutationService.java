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
 * 槽位变更执行器。
 *
 * <p>一次会话中的约束不是只有“当前选择值”，而是同时维护三种状态：</p>
 * <p>included = 用户当前明确需要的值；excluded = 用户明确排除的值；
 * unconstrained = 用户明确表示“不限制”的字段。</p>
 *
 * <p>正常路径只执行 IntentAgent 产出的结构化 ConstraintOperation，避免 Java 再扫描原文后
 * 与模型语义发生二次冲突；只有模型失败的 fallback 路径才通过关键词补齐 CLEAR / REMOVE。</p>
 */
@Service
public class SlotMutationService {
    private final SlotOptionService options;

    public SlotMutationService(SlotOptionService options) {
        this.options = options;
    }

    /**
     * 模型失败后的关键词 fallback。
     *
     * <p>这里只识别非常确定的“不限/不要”语义，并直接在 included、excluded、unconstrained
     * 三份状态之间移动值。正常模型成功路径不要调用本方法，否则会出现模型结果被规则重复解释的问题。</p>
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

        // fallback 只在字典允许值范围内匹配，避免把任意自然语言片段写入槽位状态。
        for (String field : SlotOptionService.SLOT_NAMES) {
            if (clears(text, field)) {
                // CLEAR = 清空正向/排除条件，并显式标记该字段“不限制”。
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
                    // REMOVE = 从 included 删除，同时写入 excluded，后续检索需要显式排除。
                    included = replace(included, field, without(values(included, field), value));
                    excluded = replace(excluded, field, append(values(excluded, field), value));
                    unconstrained.remove(field);
                }
            }
        }
        return new SlotMutation(included, excluded, unconstrained);
    }

    /**
     * 正常模型成功路径：执行已经过字典过滤的结构化 operations。
     *
     * <p>同一字段内的优先级为 CLEAR &gt; SET &gt; ADD/REMOVE：</p>
     * <p>CLEAR 表示完全取消该维度约束；SET 表示用本轮值整体替换；只有没有 CLEAR/SET 时，
     * 才逐条执行增量 ADD/REMOVE。input 参数仅为兼容现有调用签名保留，不参与正常路径语义判断。</p>
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
            // 先按字段分组，才能在同一维度上实现 CLEAR/SET 的覆盖优先级。
            Map<String, List<ConstraintOperation>> grouped = new LinkedHashMap<>();
            for (ConstraintOperation operation : operations) {
                if (operation == null || operation.field() == null || operation.op() == null
                        || !SlotOptionService.SLOT_NAMES.contains(operation.field())) continue;
                grouped.computeIfAbsent(operation.field(), ignored -> new ArrayList<>()).add(operation);
            }

            for (Map.Entry<String, List<ConstraintOperation>> entry : grouped.entrySet()) {
                String field = entry.getKey();
                List<ConstraintOperation> fieldOperations = entry.getValue();

                // CLEAR 优先级最高：当前维度的历史 included/excluded 都失效。
                if (fieldOperations.stream().anyMatch(operation -> operation.op() == ConstraintOperationType.CLEAR)) {
                    included = replace(included, field, List.of());
                    excluded = replace(excluded, field, List.of());
                    unconstrained.add(field);
                    continue;
                }

                // 同一字段出现多个 SET 时，以最后一个为准，符合“本轮后表达覆盖前表达”的语义。
                ConstraintOperation set = fieldOperations.stream()
                        .filter(operation -> operation.op() == ConstraintOperationType.SET)
                        .reduce((first, last) -> last).orElse(null);
                if (set != null) {
                    List<String> values = set.values() == null ? List.of() : set.values();
                    included = replace(included, field, values);
                    // 被重新选中的值不能继续留在 excluded 中。
                    excluded = replace(excluded, field, withoutAny(values(excluded, field), values));
                    unconstrained.remove(field);
                    continue;
                }

                // 没有整体覆盖操作时，再执行增量修改。
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

    /** 将不可变/空 Set 转成当前方法可安全修改的有序集合。 */
    private Set<String> mutableSet(Set<String> source) {
        return new LinkedHashSet<>(source == null ? Set.of() : source);
    }

    /** fallback 路径中识别少量明确的“不限制该维度”表达。 */
    private boolean clears(String text, String field) {
        return switch (field) {
            case "city" -> text.contains("城市不限") || text.contains("地点不限");
            case "budget" -> text.contains("预算不限") || text.contains("不限制预算");
            case "style" -> text.contains("风格不限");
            case "activityType" -> text.contains("类型不限") || text.contains("活动不限");
            default -> false;
        };
    }

    /** 按字段名读取 SlotBundle 中对应维度的值。 */
    private List<String> values(SlotBundle slots, String field) {
        return switch (field) {
            case "city" -> slots.city();
            case "location" -> slots.location();
            case "experienceGoal" -> slots.experienceGoal();
            case "companion" -> slots.companion();
            case "budget" -> slots.budget();
            case "activityType" -> slots.activityType();
            case "style" -> slots.style();
            case "duration" -> slots.duration();
            default -> slots.feature();
        };
    }

    /** SlotBundle 是不可变对象，因此每次字段修改都通过重建对象完成。 */
    private SlotBundle replace(SlotBundle s, String f, List<String> v) {
        return new SlotBundle(
                f.equals("city") ? v : s.city(), f.equals("location") ? v : s.location(),
                f.equals("experienceGoal") ? v : s.experienceGoal(), f.equals("companion") ? v : s.companion(),
                f.equals("budget") ? v : s.budget(), f.equals("activityType") ? v : s.activityType(),
                f.equals("style") ? v : s.style(), f.equals("duration") ? v : s.duration(),
                f.equals("feature") ? v : s.feature());
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

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
 * 九维普通槽位的唯一状态变更执行器。
 *
 * <p>会话约束同时维护三种状态：included = 用户当前明确需要的值；
 * excluded = 用户明确排除的值；unconstrained = 用户明确表示“不限制”的字段。</p>
 *
 * <p>本服务只执行 IntentAgent 已经产出的结构化 ConstraintOperation，不再读取或二次解析用户原文。
 * 模型异常时需要的保守规则兜底也必须先转换成 ConstraintOperation，再进入同一执行链。</p>
 */
@Service
public class SlotMutationService {

    /**
     * 将本轮 operations 应用到当前 included / excluded / unconstrained 状态。
     *
     * <p>同一字段内的优先级为 CLEAR &gt; SET &gt; ADD/REMOVE：</p>
     * <p>CLEAR 表示完全取消该维度约束；SET 表示用本轮值整体替换；只有没有 CLEAR/SET 时，
     * 才逐条执行增量 ADD/REMOVE。</p>
     */
    public SlotMutation apply(List<ConstraintOperation> operations,
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

    private List<String> appendAll(List<String> source, List<String> values) {
        return java.util.stream.Stream.concat(source.stream(), values.stream()).distinct().toList();
    }

    private List<String> withoutAny(List<String> source, List<String> values) {
        return source.stream().filter(v -> !values.contains(v)).toList();
    }
}

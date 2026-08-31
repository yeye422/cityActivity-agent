package com.city.service.slot;

import com.city.exception.CityException;
import com.city.mapper.SlotOptionMapper;
import com.city.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 九维槽位字典服务。
 *
 * <p>数据库中的启用标签是槽位值的唯一合法来源。IntentAgent 输出、历史 SessionState、
 * 前端 context 等外部输入在进入推荐检索前，都应通过本服务做清洗或校验，避免模型产生的
 * 非法标签直接进入 SQL 查询与排序链路。</p>
 */
@Service
public class SlotOptionService {

    /**
     * 系统支持的九维槽位字段名。
     * 字段名同时被 Prompt、SessionState、ConstraintOperation 和数据库字典使用，修改时需要保持各层一致。
     */
    public static final List<String> SLOT_NAMES = List.of(
            "city", "location", "experienceGoal", "companion", "budget", "activityType", "style", "duration", "feature"
    );

    private final SlotOptionMapper slotOptionMapper;

    public SlotOptionService(SlotOptionMapper slotOptionMapper) {
        this.slotOptionMapper = slotOptionMapper;
    }

    /**
     * 读取九个槽位当前启用的全部字典值。
     *
     * <p>使用 LinkedHashMap 保持 SLOT_NAMES 的稳定顺序，便于 Prompt 构造、Trace 展示和调试时对齐。</p>
     */
    public Map<String, List<String>> findAllOptions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String slotName : SLOT_NAMES) {
            result.put(slotName, slotOptionMapper.findEnabledValues(slotName));
        }
        return result;
    }

    /**
     * 对槽位做容错式清洗：只保留当前字典仍然启用的值，并自动去重。
     *
     * <p>这里采用“丢弃非法值”而不是抛异常，主要用于模型输出和历史会话恢复；
     * 即使字典已经调整，旧 Session 也不会因此阻断新的推荐请求。</p>
     */
    public SlotBundle sanitize(SlotBundle slots) {
        SlotBundle safe = slots == null ? SlotBundle.empty() : slots;
        Map<String, List<String>> options = findAllOptions();
        return new SlotBundle(
                sanitizeValues("city", safe.city(), options),
                sanitizeValues("location", safe.location(), options),
                sanitizeValues("experienceGoal", safe.experienceGoal(), options),
                sanitizeValues("companion", safe.companion(), options),
                sanitizeValues("budget", safe.budget(), options),
                sanitizeValues("activityType", safe.activityType(), options),
                sanitizeValues("style", safe.style(), options),
                sanitizeValues("duration", safe.duration(), options),
                sanitizeValues("feature", safe.feature(), options)
        );
    }

    /**
     * 对槽位做严格校验：发现任意未启用标签立即抛错。
     * 适合需要保证调用方数据绝对合法的边界，而不是模型输出的容错入口。
     */
    public void validate(SlotBundle slots) {
        SlotBundle safe = slots == null ? SlotBundle.empty() : slots;
        Map<String, List<String>> options = findAllOptions();
        validateSlot("city", safe.city(), options);
        validateSlot("location", safe.location(), options);
        validateSlot("experienceGoal", safe.experienceGoal(), options);
        validateSlot("companion", safe.companion(), options);
        validateSlot("budget", safe.budget(), options);
        validateSlot("activityType", safe.activityType(), options);
        validateSlot("style", safe.style(), options);
        validateSlot("duration", safe.duration(), options);
        validateSlot("feature", safe.feature(), options);
    }

    /** 过滤一个槽位的非法值，并保持输入顺序去重。 */
    private List<String> sanitizeValues(String slotName, List<String> values, Map<String, List<String>> options) {
        Set<String> allowed = Set.copyOf(options.getOrDefault(slotName, List.of()));
        if (values == null || values.isEmpty() || allowed.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(allowed::contains)
                .distinct()
                .toList();
    }

    /** 检查一个槽位中的每个值是否都存在于当前启用字典。 */
    private void validateSlot(String slotName, List<String> values, Map<String, List<String>> options) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Set<String> allowed = Set.copyOf(options.getOrDefault(slotName, List.of()));
        for (String value : values) {
            if (!allowed.contains(value)) {
                throw new CityException("非法槽位标签: " + slotName + "=" + value);
            }
        }
    }
}

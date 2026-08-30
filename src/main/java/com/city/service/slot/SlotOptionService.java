package com.city.service.slot;

import com.city.exception.CityException;
import com.city.mapper.SlotOptionMapper;
import com.city.model.SlotBundle;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SlotOptionService {
    public static final List<String> SLOT_NAMES = List.of(
            "city", "location", "experienceGoal", "companion", "budget", "activityType", "style", "duration", "feature"
    );

    private final SlotOptionMapper slotOptionMapper;

    public SlotOptionService(SlotOptionMapper slotOptionMapper) {
        this.slotOptionMapper = slotOptionMapper;
    }

    public Map<String, List<String>> findAllOptions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String slotName : SLOT_NAMES) {
            result.put(slotName, slotOptionMapper.findEnabledValues(slotName));
        }
        return result;
    }

    /**
     * Keep only values from the active city dictionary.
     * This also cleans stale session data before it participates in a new search.
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

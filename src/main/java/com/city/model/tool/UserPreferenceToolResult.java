package com.city.model.tool;

import com.city.enums.PreferencePolarity;
import com.city.model.PreferenceFact;

import java.util.List;

/** Agent 可见的长期偏好只读视图。 */
public record UserPreferenceToolResult(
        String focus,
        List<Preference> preferences
) {
    public UserPreferenceToolResult {
        focus = focus == null ? "" : focus.trim();
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
    }

    public static UserPreferenceToolResult from(String focus, List<PreferenceFact> facts) {
        return new UserPreferenceToolResult(
                focus,
                facts == null ? List.of() : facts.stream()
                        .filter(fact -> fact != null && Boolean.TRUE.equals(fact.getActive()))
                        .map(fact -> new Preference(
                                fact.getSlotName(),
                                fact.getSlotValue(),
                                fact.getPolarity(),
                                fact.getSource()
                        ))
                        .toList()
        );
    }

    public record Preference(
            String slotName,
            String slotValue,
            PreferencePolarity polarity,
            String source
    ) {}
}

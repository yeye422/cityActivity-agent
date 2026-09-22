package com.city.enums;

/** 当前由 Java 规则要求用户补充的必要字段。 */
public enum ClarifyField {
    CITY("city"),
    DATE("date"),
    TIME("time");

    private final String key;

    ClarifyField(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static ClarifyField parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        for (ClarifyField field : values()) {
            if (field.name().equalsIgnoreCase(normalized) || field.key.equalsIgnoreCase(normalized)) {
                return field;
            }
        }
        return null;
    }
}

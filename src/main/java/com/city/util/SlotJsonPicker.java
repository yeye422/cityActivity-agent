package com.city.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 LLM 返回的 JSON 槽位中提取标准标签。
 */
public class SlotJsonPicker {
    private SlotJsonPicker() {}

    public static List<String> pick(JsonNode root, String field, Map<String, List<String>> options) {
        List<String> allowed = options.getOrDefault(field, List.of());
        JsonNode node = root.path(field);
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> addIfAllowed(result, item.asText(), allowed, field));
        } else if (node.isTextual()) {
            addIfAllowed(result, node.asText(), allowed, field);
        }
        return result;
    }

    private static void addIfAllowed(List<String> result, String value, List<String> allowed, String field) {
        if (value == null) {
            return;
        }
        for (String candidate : value.split("\\s*[,，、;；/|]\\s*")) {
            String normalized = candidate.trim();

            // 直接匹配
            if (allowed.contains(normalized) && !result.contains(normalized)) {
                result.add(normalized);
                continue;
            }

            // activityTime 字段的智能映射
            if ("activityTime".equals(field)) {
                List<String> mapped = mapActivityTime(normalized, allowed);
                for (String mappedValue : mapped) {
                    if (!result.contains(mappedValue)) {
                        result.add(mappedValue);
                    }
                }
            }
        }
    }

    /**
     * 将用户的时间表述映射到标准槽位。
     * 例如："周六" → ["周六上午", "周六下午", "周六晚上"]
     */
    private static List<String> mapActivityTime(String userInput, List<String> allowed) {
        List<String> result = new ArrayList<>();

        // "周六" → 周六所有时段
        if ("周六".equals(userInput)) {
            addIfExists(result, "周六上午", allowed);
            addIfExists(result, "周六下午", allowed);
            addIfExists(result, "周六晚上", allowed);
        }
        // "周日" → 周日所有时段
        else if ("周日".equals(userInput)) {
            addIfExists(result, "周日上午", allowed);
            addIfExists(result, "周日下午", allowed);
            addIfExists(result, "周日晚上", allowed);
            addIfExists(result, "周日", allowed); // 保留原有的"周日"槽位
        }
        // "上午" → 周六上午 + 周日上午
        else if ("上午".equals(userInput)) {
            addIfExists(result, "周六上午", allowed);
            addIfExists(result, "周日上午", allowed);
        }
        // "下午" → 周六下午 + 周日下午
        else if ("下午".equals(userInput)) {
            addIfExists(result, "周六下午", allowed);
            addIfExists(result, "周日下午", allowed);
        }
        // "晚上" → 周六晚上 + 周日晚上
        else if ("晚上".equals(userInput) || "夜晚".equals(userInput)) {
            addIfExists(result, "周六晚上", allowed);
            addIfExists(result, "周日晚上", allowed);
        }
        // "中午" → 周六下午 + 周日下午
        else if ("中午".equals(userInput) || "午后".equals(userInput)) {
            addIfExists(result, "周六下午", allowed);
            addIfExists(result, "周日下午", allowed);
        }
        // "早上" → 周六上午 + 周日上午
        else if ("早上".equals(userInput) || "早晨".equals(userInput) || "清晨".equals(userInput)) {
            addIfExists(result, "周六上午", allowed);
            addIfExists(result, "周日上午", allowed);
        }
        // "周末" → 周六周日所有时段
        else if ("周末".equals(userInput)) {
            addIfExists(result, "周六上午", allowed);
            addIfExists(result, "周六下午", allowed);
            addIfExists(result, "周六晚上", allowed);
            addIfExists(result, "周日上午", allowed);
            addIfExists(result, "周日下午", allowed);
            addIfExists(result, "周日晚上", allowed);
            addIfExists(result, "周日", allowed);
        }

        return result;
    }

    private static void addIfExists(List<String> result, String value, List<String> allowed) {
        if (allowed.contains(value)) {
            result.add(value);
        }
    }
}
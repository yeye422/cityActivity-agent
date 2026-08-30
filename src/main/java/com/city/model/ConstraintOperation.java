package com.city.model;

import com.city.enums.ConstraintOperationType;

import java.util.List;

/** LLM 只产出语义补丁，最终是否执行由 Java 字典校验决定。 */
public record ConstraintOperation(
        String field,
        ConstraintOperationType op,
        List<String> values,
        String raw
) {
}

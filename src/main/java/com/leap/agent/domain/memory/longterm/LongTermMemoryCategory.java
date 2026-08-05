package com.leap.agent.domain.memory.longterm;

import java.util.Arrays;
import java.util.Optional;

/**
 * 长期记忆主分类。
 */
public enum LongTermMemoryCategory {
    IDENTITY("identity", "身份信息"),
    PREFERENCE("preference", "稳定偏好"),
    POLICY("policy", "硬性约束"),
    FACT("fact", "长期事实"),
    TROUBLESHOOTING_CASE("troubleshooting_case", "排障案例"),
    TOOL_LESSON("tool_lesson", "工具经验"),
    GENERAL("general", "通用记忆");

    private final String value;
    private final String displayName;

    LongTermMemoryCategory(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    public String value() {
        return value;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<LongTermMemoryCategory> fromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(category -> category.value.equalsIgnoreCase(value)
                        || category.name().equalsIgnoreCase(value))
                .findFirst();
    }
}

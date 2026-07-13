package com.leap.agent.domain.memory.preference;

import java.util.Arrays;
import java.util.Optional;

/**
 * 开放偏好的受控分类。
 * 分类保持少量枚举，避免 LLM 把偏好空间发散成不可治理的自由标签。
 */
public enum PreferenceItemCategory {
    RESPONSE_BEHAVIOR("response_behavior", "回复行为"),
    OPS_METHODOLOGY("ops_methodology", "运维方法"),
    TOOL_USAGE("tool_usage", "工具使用"),
    DOMAIN_CONVENTION("domain_convention", "领域约定");

    private final String value;
    private final String displayName;

    PreferenceItemCategory(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    public String value() {
        return value;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<PreferenceItemCategory> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(category -> category.value.equalsIgnoreCase(value) || category.name().equalsIgnoreCase(value))
                .findFirst();
    }
}

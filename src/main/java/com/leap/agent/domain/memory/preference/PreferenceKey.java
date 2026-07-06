package com.leap.agent.domain.memory.preference;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 受控的全局偏好键集合。
 * 限制 key 的范围，避免规则抽取和 LLM 抽取把偏好文件写成无约束 KV 仓库。
 */
public enum PreferenceKey {
    REPLY_LANGUAGE("reply_language", "默认回复语言"),
    REPLY_STYLE("reply_style", "默认回复风格"),
    CLS_REGION("cls_region", "CLS默认地域"),
    TIME_RANGE("time_range", "默认时间范围"),
    SERVICE_SCOPE("service_scope", "默认服务范围"),
    CUSTOM_RULES("custom_rules", "额外规则");

    private final String key;
    private final String displayName;

    PreferenceKey(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<PreferenceKey> fromKey(String key) {
        return Arrays.stream(values())
                .filter(item -> item.key.equals(key))
                .findFirst();
    }

    /**
     * 对外输出时按预设顺序稳定排序，减少调试接口和 prompt 内容的抖动。
     */
    public static Map<String, String> orderedView(Map<String, String> preferences) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (PreferenceKey key : values()) {
            String value = preferences.get(key.key());
            if (value != null && !value.isBlank()) {
                ordered.put(key.key(), value);
            }
        }
        return ordered;
    }
}

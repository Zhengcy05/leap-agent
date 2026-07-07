package com.leap.agent.domain.memory.preference;

/**
 * 结构化偏好条目。
 */
public record PreferenceEntry(
        String key,
        String value,
        PreferenceSource source,
        long updatedAt,
        long version
) {
}

package com.leap.agent.domain.memory.preference;

/**
 * 开放偏好条目。
 * 用来承接无法无损落入固定槽位、但对后续对话有长期价值的行为约定。
 */
public record PreferenceItem(
        String id,
        String category,
        String content,
        String scope,
        double confidence,
        PreferenceSource source,
        long updatedAt,
        long version,
        PreferenceItemStatus status
) {
}

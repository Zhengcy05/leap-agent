package com.leap.agent.domain.memory.longterm;

import java.util.Locale;
import java.util.Optional;

/**
 * 长期记忆生命周期状态。
 */
public enum LongTermMemoryStatus {
    ACTIVE,
    ARCHIVED;

    public static Optional<LongTermMemoryStatus> fromName(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

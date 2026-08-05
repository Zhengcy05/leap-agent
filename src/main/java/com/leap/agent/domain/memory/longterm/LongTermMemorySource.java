package com.leap.agent.domain.memory.longterm;

import java.util.Locale;
import java.util.Optional;

/**
 * 长期记忆写入来源。
 */
public enum LongTermMemorySource {
    BOOTSTRAP,
    RULE,
    LLM,
    LEGACY;

    public static Optional<LongTermMemorySource> fromName(String value) {
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

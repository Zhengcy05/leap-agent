package com.leap.agent.domain.memory.longterm;

import java.util.List;

/**
 * LLM 抽取出的长期记忆候选。
 */
public record LongTermMemoryCandidate(
        String sessionId,
        String category,
        String content,
        List<String> tags,
        double importance,
        double confidence,
        LongTermMemorySource source
) {
    /**
     * 归一化候选中的可变集合。
     */
    public LongTermMemoryCandidate {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}

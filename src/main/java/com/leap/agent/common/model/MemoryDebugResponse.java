package com.leap.agent.common.model;

import com.leap.agent.domain.memory.shortterm.ShortTermMessage;
import com.leap.agent.domain.memory.preference.PreferenceEntry;

import java.util.List;
import java.util.Map;

/**
 * 记忆调试接口响应体。
 * 仅用于观测当前记忆状态，不参与主对话链路。
 */
public record MemoryDebugResponse(
        Map<String, String> preferences,
        Map<String, PreferenceEntryView> preferenceDetails,
        List<SessionMemorySummary> sessions,
        SessionMemoryDetail session
) {

    public record PreferenceEntryView(
            String value,
            String source,
            long updatedAt,
            long version
    ) {
        public static PreferenceEntryView from(PreferenceEntry entry) {
            return new PreferenceEntryView(
                    entry.value(),
                    entry.source() != null ? entry.source().name() : null,
                    entry.updatedAt(),
                    entry.version()
            );
        }
    }

    /**
     * 会话摘要视图，用于列表页快速查看活跃 session 状态。
     */
    public record SessionMemorySummary(
            String sessionId,
            int messagePairCount,
            long createTime
    ) {
    }

    /**
     * 指定 session 的完整短期记忆明细。
     */
    public record SessionMemoryDetail(
            String sessionId,
            int messagePairCount,
            long createTime,
            List<ShortTermMessage> messages
    ) {
    }
}

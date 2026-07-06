package com.leap.agent.domain.memory.shortterm;

/**
 * 短期记忆中的单条对话消息。
 */
public record ShortTermMessage(String role, String content, long timestamp) {
}

package com.leap.agent.domain.memory.shortterm;

import java.util.List;

/**
 * 短期记忆快照。
 */
public record ShortTermMemorySnapshot(List<ShortTermMessage> messages, int messagePairCount) {

    public ShortTermMemorySnapshot {
        // 对外暴露不可变列表，避免调试接口或 prompt 组装方误改快照内容。
        messages = List.copyOf(messages);
    }

    /**
     * 渲染给系统提示词使用的对话历史段落。
     */
    public String renderPromptSection() {
        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("【短期记忆 / 对话历史】\n");
        for (ShortTermMessage message : messages) {
            if ("user".equals(message.role())) {
                builder.append("用户: ").append(message.content()).append("\n");
            } else if ("assistant".equals(message.role())) {
                builder.append("助手: ").append(message.content()).append("\n");
            }
        }
        return builder.toString().trim();
    }
}

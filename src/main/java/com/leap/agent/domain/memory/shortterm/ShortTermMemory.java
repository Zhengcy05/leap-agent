package com.leap.agent.domain.memory.shortterm;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话级短期记忆窗口。
 */
public class ShortTermMemory {

    private final List<ShortTermMessage> messages = new ArrayList<>();
    private final int maxWindowSize;

    public ShortTermMemory(int maxWindowSize) {
        this.maxWindowSize = maxWindowSize;
    }

    public void addTurn(String userQuestion, String aiAnswer) {
        long now = System.currentTimeMillis();
        messages.add(new ShortTermMessage("user", userQuestion, now));
        messages.add(new ShortTermMessage("assistant", aiAnswer, now));

        // 短期记忆只保留最近 N 轮完整对话；裁剪时按消息粒度从最老的 turn 开始移除。
        int maxMessages = maxWindowSize * 2;
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }
    }

    public ShortTermMemorySnapshot snapshot() {
        // 快照返回副本，调用方只能读不能回写，避免 session 内部状态被外部篡改。
        return new ShortTermMemorySnapshot(new ArrayList<>(messages), messages.size() / 2);
    }

    public void clear() {
        messages.clear();
    }
}

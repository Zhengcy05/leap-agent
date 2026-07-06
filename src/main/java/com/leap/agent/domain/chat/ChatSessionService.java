package com.leap.agent.domain.chat;

import com.leap.agent.common.config.MemoryProperties;
import com.leap.agent.domain.memory.shortterm.ShortTermMemory;
import com.leap.agent.domain.memory.shortterm.ShortTermMemorySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 维护内存态聊天会话，并裁剪对话历史窗口。
 */
@Service
public class ChatSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionService.class);

    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final MemoryProperties memoryProperties;

    public ChatSessionService(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties;
    }

    /**
     * 获取已有会话；当客户端没有传入会话 ID 时创建一个新会话。
     */
    public ChatSession getOrCreateSession(String sessionId) {
        String safeSessionId = sessionId;
        if (safeSessionId == null || safeSessionId.isEmpty()) {
            safeSessionId = UUID.randomUUID().toString();
        }
        // 会话创建时固化当前窗口大小，避免运行期配置变化影响已存在会话。
        return sessions.computeIfAbsent(
                safeSessionId,
                id -> new ChatSession(id, memoryProperties.getShortTerm().getMaxWindowSize())
        );
    }

    /**
     * 查询已有会话但不自动创建，供管理类接口使用。
     */
    public Optional<ChatSession> getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 列出所有活动会话的快照。
     */
    public List<ChatSessionSnapshot> listSessionSnapshots() {
        return sessions.values().stream()
                .map(ChatSession::snapshot)
                // 调试视图优先展示最近创建的会话。
                .sorted((left, right) -> Long.compare(right.createTime(), left.createTime()))
                .collect(Collectors.toList());
    }

    /**
     * 单个聊天会话的线程安全可变状态。
     */
    public static class ChatSession {
        private final String sessionId;
        private final ShortTermMemory shortTermMemory;
        private final long createTime;
        private final ReentrantLock lock;

        public ChatSession(String sessionId, int maxWindowSize) {
            this.sessionId = sessionId;
            this.shortTermMemory = new ShortTermMemory(maxWindowSize);
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        /**
         * 追加一组用户/助手消息，并只保留最近的历史窗口。
         */
        public void addMessage(String userQuestion, String aiAnswer) {
            lock.lock();
            try {
                shortTermMemory.addTurn(userQuestion, aiAnswer);

                logger.debug("会话 {} 更新历史消息，当前消息对数: {}",
                        sessionId, shortTermMemory.snapshot().messagePairCount());
            } finally {
                lock.unlock();
            }
        }

        /**
         * 返回历史快照，避免调用方修改内部会话状态。
         */
        public ShortTermMemorySnapshot getHistorySnapshot() {
            lock.lock();
            try {
                return shortTermMemory.snapshot();
            } finally {
                lock.unlock();
            }
        }

        /**
         * 清空当前会话保留的所有历史消息。
         */
        public void clearHistory() {
            lock.lock();
            try {
                shortTermMemory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 统计完整的用户/助手消息对数量。
         */
        public int getMessagePairCount() {
            lock.lock();
            try {
                return shortTermMemory.snapshot().messagePairCount();
            } finally {
                lock.unlock();
            }
        }

        public ChatSessionSnapshot snapshot() {
            lock.lock();
            try {
                ShortTermMemorySnapshot memorySnapshot = shortTermMemory.snapshot();
                return new ChatSessionSnapshot(sessionId, createTime, memorySnapshot);
            } finally {
                lock.unlock();
            }
        }

        public long getCreateTime() {
            return createTime;
        }
    }

    /**
     * 调试接口使用的会话快照。
     */
    public record ChatSessionSnapshot(
            String sessionId,
            long createTime,
            ShortTermMemorySnapshot memory
    ) {
    }
}

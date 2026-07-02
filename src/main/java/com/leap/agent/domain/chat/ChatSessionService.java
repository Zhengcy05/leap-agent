package com.leap.agent.domain.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 维护内存态聊天会话，并裁剪对话历史窗口。
 */
@Service
public class ChatSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ChatSessionService.class);
    private static final int MAX_WINDOW_SIZE = 6;

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    /**
     * 获取已有会话；当客户端没有传入会话 ID 时创建一个新会话。
     */
    public ChatSession getOrCreateSession(String sessionId) {
        String safeSessionId = sessionId;
        if (safeSessionId == null || safeSessionId.isEmpty()) {
            safeSessionId = UUID.randomUUID().toString();
        }
        return sessions.computeIfAbsent(safeSessionId, ChatSession::new);
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
     * 单个聊天会话的线程安全可变状态。
     */
    public static class ChatSession {
        private final String sessionId;
        private final List<Map<String, String>> messageHistory;
        private final long createTime;
        private final ReentrantLock lock;

        public ChatSession(String sessionId) {
            this.sessionId = sessionId;
            this.messageHistory = new ArrayList<>();
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        /**
         * 追加一组用户/助手消息，并只保留最近的历史窗口。
         */
        public void addMessage(String userQuestion, String aiAnswer) {
            lock.lock();
            try {
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                int maxMessages = MAX_WINDOW_SIZE * 2;
                while (messageHistory.size() > maxMessages) {
                    messageHistory.remove(0);
                    if (!messageHistory.isEmpty()) {
                        messageHistory.remove(0);
                    }
                }

                logger.debug("会话 {} 更新历史消息，当前消息对数: {}",
                        sessionId, messageHistory.size() / 2);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 返回历史快照，避免调用方修改内部会话状态。
         */
        public List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
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
                messageHistory.clear();
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
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }

        public long getCreateTime() {
            return createTime;
        }
    }
}

package com.leap.agent.domain.memory.longterm;

import java.util.List;

/**
 * 长期记忆仓储接口。
 *
 * <p>当前以 ownerId 聚合保存，保留后续按用户/租户隔离和替换数据库实现的空间。</p>
 */
public interface LongTermMemoryRepository {

    List<LongTermMemoryEntry> loadAll(String ownerId);

    void saveAll(String ownerId, List<LongTermMemoryEntry> entries);
}

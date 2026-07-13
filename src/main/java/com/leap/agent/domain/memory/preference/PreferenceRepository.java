package com.leap.agent.domain.memory.preference;

import java.util.Map;
import java.util.List;

/**
 * 偏好仓储接口，保持与未来数据库实现兼容。
 */
public interface PreferenceRepository {

    void save(String ownerId, PreferenceEntry entry);

    Map<String, PreferenceEntry> loadAll(String ownerId);

    void saveItem(String ownerId, PreferenceItem item);

    List<PreferenceItem> loadItems(String ownerId);
}

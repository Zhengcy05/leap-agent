package com.leap.agent.domain.memory.preference;

import java.util.Map;

/**
 * 偏好仓储接口，保持与未来数据库实现兼容。
 */
public interface PreferenceRepository {

    void save(String ownerId, String key, String value);

    Map<String, String> loadAll(String ownerId);
}

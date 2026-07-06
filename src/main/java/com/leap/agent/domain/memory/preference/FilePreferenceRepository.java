package com.leap.agent.domain.memory.preference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于本地 JSON 文件的偏好仓储。
 */
@Repository
public class FilePreferenceRepository implements PreferenceRepository {

    private static final Logger logger = LoggerFactory.getLogger(FilePreferenceRepository.class);
    private static final TypeReference<Map<String, Map<String, Object>>> STORAGE_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final MemoryProperties memoryProperties;

    public FilePreferenceRepository(ObjectMapper objectMapper, MemoryProperties memoryProperties) {
        this.objectMapper = objectMapper;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public synchronized void save(String ownerId, PreferenceEntry entry) {
        // 文件结构固定为 { ownerId -> { key -> value } }，后续切数据库时可以保留 owner 维度。
        Map<String, Map<String, PreferenceEntry>> data = readStorage();
        data.computeIfAbsent(ownerId, ignored -> new LinkedHashMap<>()).put(entry.key(), entry);
        writeStorage(data);
    }

    @Override
    public synchronized Map<String, PreferenceEntry> loadAll(String ownerId) {
        Map<String, Map<String, PreferenceEntry>> data = readStorage();
        Map<String, PreferenceEntry> values = data.get(ownerId);
        if (values == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, PreferenceEntry>> readStorage() {
        Path path = storagePath();
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }

        try {
            Map<String, Map<String, Object>> raw = objectMapper.readValue(path.toFile(), STORAGE_TYPE);
            Map<String, Map<String, PreferenceEntry>> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> ownerEntry : raw.entrySet()) {
                Map<String, PreferenceEntry> ownerPreferences = new LinkedHashMap<>();
                for (Map.Entry<String, Object> preferenceEntry : ownerEntry.getValue().entrySet()) {
                    ownerPreferences.put(preferenceEntry.getKey(),
                            toPreferenceEntry(preferenceEntry.getKey(), preferenceEntry.getValue()));
                }
                normalized.put(ownerEntry.getKey(), ownerPreferences);
            }
            return normalized;
        } catch (IOException e) {
            logger.warn("读取偏好文件失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void writeStorage(Map<String, Map<String, PreferenceEntry>> data) {
        Path path = storagePath();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                // storagePath 允许配置到任意相对路径，写入前补齐目录。
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), data);
        } catch (IOException e) {
            logger.warn("写入偏好文件失败: {}", e.getMessage());
        }
    }

    private Path storagePath() {
        return Path.of(memoryProperties.getPreference().getStoragePath());
    }

    private PreferenceEntry toPreferenceEntry(String key, Object rawValue) {
        if (rawValue instanceof String stringValue) {
            return new PreferenceEntry(key, stringValue, PreferenceSource.LEGACY, 0L, 1L);
        }

        if (!(rawValue instanceof Map<?, ?> rawMap)) {
            return new PreferenceEntry(key, "", PreferenceSource.LEGACY, 0L, 1L);
        }

        Map<String, Object> map = new HashMap<>();
        rawMap.forEach((entryKey, entryValue) -> map.put(String.valueOf(entryKey), entryValue));

        String value = String.valueOf(map.getOrDefault("value", ""));
        PreferenceSource source = parseSource(String.valueOf(map.getOrDefault("source", PreferenceSource.LEGACY.name())));
        long updatedAt = parseLong(map.get("updatedAt"));
        long version = parseLong(map.get("version"));
        if (version <= 0) {
            version = 1L;
        }
        return new PreferenceEntry(key, value, source, updatedAt, version);
    }

    private PreferenceSource parseSource(String source) {
        try {
            return PreferenceSource.valueOf(source);
        } catch (Exception ignored) {
            return PreferenceSource.LEGACY;
        }
    }

    private long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}

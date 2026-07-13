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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于本地 JSON 文件的偏好仓储。
 */
@Repository
public class FilePreferenceRepository implements PreferenceRepository {

    private static final Logger logger = LoggerFactory.getLogger(FilePreferenceRepository.class);
    private static final TypeReference<Map<String, Object>> STORAGE_TYPE = new TypeReference<>() {
    };
    private static final String PREFERENCES_SECTION = "preferences";
    private static final String PREFERENCE_ITEMS_SECTION = "preferenceItems";

    private final ObjectMapper objectMapper;
    private final MemoryProperties memoryProperties;

    public FilePreferenceRepository(ObjectMapper objectMapper, MemoryProperties memoryProperties) {
        this.objectMapper = objectMapper;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public synchronized void save(String ownerId, PreferenceEntry entry) {
        StorageData data = readStorage();
        data.preferences().computeIfAbsent(ownerId, ignored -> new LinkedHashMap<>()).put(entry.key(), entry);
        writeStorage(data);
    }

    @Override
    public synchronized Map<String, PreferenceEntry> loadAll(String ownerId) {
        StorageData data = readStorage();
        Map<String, PreferenceEntry> values = data.preferences().get(ownerId);
        if (values == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(values);
    }

    @Override
    public synchronized void saveItem(String ownerId, PreferenceItem item) {
        StorageData data = readStorage();
        List<PreferenceItem> items = data.preferenceItems().computeIfAbsent(ownerId, ignored -> new ArrayList<>());
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id().equals(item.id())) {
                items.set(index, item);
                writeStorage(data);
                return;
            }
        }
        items.add(item);
        writeStorage(data);
    }

    @Override
    public synchronized List<PreferenceItem> loadItems(String ownerId) {
        StorageData data = readStorage();
        List<PreferenceItem> values = data.preferenceItems().get(ownerId);
        if (values == null) {
            return List.of();
        }
        return new ArrayList<>(values);
    }

    private StorageData readStorage() {
        Path path = storagePath();
        if (!Files.exists(path)) {
            return StorageData.empty();
        }

        try {
            Map<String, Object> raw = objectMapper.readValue(path.toFile(), STORAGE_TYPE);
            return normalizeStorage(raw);
        } catch (IOException e) {
            logger.warn("读取偏好文件失败: {}", e.getMessage());
            return StorageData.empty();
        }
    }

    private void writeStorage(StorageData data) {
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

    @SuppressWarnings("unchecked")
    private StorageData normalizeStorage(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return StorageData.empty();
        }

        Map<String, Map<String, PreferenceEntry>> preferences;
        Map<String, List<PreferenceItem>> preferenceItems;
        if (raw.containsKey(PREFERENCES_SECTION) || raw.containsKey(PREFERENCE_ITEMS_SECTION)) {
            // 新格式拆成固定偏好和开放偏好两个区块，后续切数据库时也按这两个聚合根迁移。
            preferences = parsePreferencesSection(raw.get(PREFERENCES_SECTION));
            preferenceItems = parsePreferenceItemsSection(raw.get(PREFERENCE_ITEMS_SECTION));
        } else {
            // 兼容旧格式：{ ownerId -> { key -> PreferenceEntry } }。
            preferences = parsePreferencesSection(raw);
            preferenceItems = new LinkedHashMap<>();
        }
        return new StorageData(preferences, preferenceItems);
    }

    private Map<String, Map<String, PreferenceEntry>> parsePreferencesSection(Object rawSection) {
        Map<String, Map<String, PreferenceEntry>> normalized = new LinkedHashMap<>();
        if (!(rawSection instanceof Map<?, ?> rawOwnerMap)) {
            return normalized;
        }

        for (Map.Entry<?, ?> ownerEntry : rawOwnerMap.entrySet()) {
            if (!(ownerEntry.getValue() instanceof Map<?, ?> rawPreferenceMap)) {
                continue;
            }
            Map<String, PreferenceEntry> ownerPreferences = new LinkedHashMap<>();
            for (Map.Entry<?, ?> preferenceEntry : rawPreferenceMap.entrySet()) {
                String key = String.valueOf(preferenceEntry.getKey());
                ownerPreferences.put(key, toPreferenceEntry(key, preferenceEntry.getValue()));
            }
            normalized.put(String.valueOf(ownerEntry.getKey()), ownerPreferences);
        }
        return normalized;
    }

    private Map<String, List<PreferenceItem>> parsePreferenceItemsSection(Object rawSection) {
        Map<String, List<PreferenceItem>> normalized = new LinkedHashMap<>();
        if (!(rawSection instanceof Map<?, ?> rawOwnerMap)) {
            return normalized;
        }

        for (Map.Entry<?, ?> ownerEntry : rawOwnerMap.entrySet()) {
            List<PreferenceItem> items = new ArrayList<>();
            if (ownerEntry.getValue() instanceof List<?> rawItems) {
                for (Object rawItem : rawItems) {
                    PreferenceItem item = toPreferenceItem(rawItem);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            normalized.put(String.valueOf(ownerEntry.getKey()), items);
        }
        return normalized;
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

    private PreferenceItem toPreferenceItem(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> rawMap)) {
            return null;
        }

        // 文件仓储只做宽松读取：坏数据在这里丢弃，避免启动阶段把损坏条目带入运行期缓存。
        Map<String, Object> map = new HashMap<>();
        rawMap.forEach((entryKey, entryValue) -> map.put(String.valueOf(entryKey), entryValue));

        String id = String.valueOf(map.getOrDefault("id", ""));
        String category = String.valueOf(map.getOrDefault("category", ""));
        String content = String.valueOf(map.getOrDefault("content", ""));
        String scope = String.valueOf(map.getOrDefault("scope", "global"));
        double confidence = parseDouble(map.get("confidence"));
        PreferenceSource source = parseSource(String.valueOf(map.getOrDefault("source", PreferenceSource.LEGACY.name())));
        long updatedAt = parseLong(map.get("updatedAt"));
        long version = parseLong(map.get("version"));
        PreferenceItemStatus status = parseStatus(String.valueOf(map.getOrDefault("status", PreferenceItemStatus.ACTIVE.name())));
        if (id.isBlank() || content.isBlank()) {
            return null;
        }
        if (version <= 0) {
            version = 1L;
        }
        return new PreferenceItem(id, category, content, scope, confidence, source, updatedAt, version, status);
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

    private double parseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0D;
        }
    }

    private PreferenceItemStatus parseStatus(String status) {
        try {
            return PreferenceItemStatus.valueOf(status);
        } catch (Exception ignored) {
            return PreferenceItemStatus.ACTIVE;
        }
    }

    private record StorageData(
            Map<String, Map<String, PreferenceEntry>> preferences,
            Map<String, List<PreferenceItem>> preferenceItems
    ) {
        static StorageData empty() {
            return new StorageData(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }
}

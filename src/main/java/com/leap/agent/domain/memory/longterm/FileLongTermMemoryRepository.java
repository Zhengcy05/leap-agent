package com.leap.agent.domain.memory.longterm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于本地 JSON 文件的长期记忆仓储。
 */
@Repository
@ConditionalOnProperty(prefix = "memory.long-term", name = "repository", havingValue = "file", matchIfMissing = true)
public class FileLongTermMemoryRepository implements LongTermMemoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(FileLongTermMemoryRepository.class);
    private static final TypeReference<Map<String, Object>> STORAGE_TYPE = new TypeReference<>() {
    };
    private static final String MEMORIES_SECTION = "memories";

    private final ObjectMapper objectMapper;
    private final MemoryProperties memoryProperties;

    public FileLongTermMemoryRepository(ObjectMapper objectMapper, MemoryProperties memoryProperties) {
        this.objectMapper = objectMapper;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public synchronized List<LongTermMemoryEntry> loadAll(String ownerId) {
        StorageData data = readStorage();
        List<LongTermMemoryEntry> values = data.memories().get(ownerId);
        if (values == null) {
            return List.of();
        }
        return new ArrayList<>(values);
    }

    @Override
    public synchronized void saveAll(String ownerId, List<LongTermMemoryEntry> entries) {
        // 读-改-写保留其他 owner 的内容；synchronized 只覆盖当前进程，后续切数据库后再做跨进程并发治理。
        StorageData data = readStorage();
        data.memories().put(ownerId, new ArrayList<>(entries));
        writeStorage(data);
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
            logger.warn("读取长期记忆文件失败: {}", e.getMessage());
            return StorageData.empty();
        }
    }

    private void writeStorage(StorageData data) {
        Path path = storagePath();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), data);
        } catch (IOException e) {
            logger.warn("写入长期记忆文件失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private StorageData normalizeStorage(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return StorageData.empty();
        }

        Object rawSection = raw.containsKey(MEMORIES_SECTION) ? raw.get(MEMORIES_SECTION) : raw;
        Map<String, List<LongTermMemoryEntry>> memories = new LinkedHashMap<>();
        if (!(rawSection instanceof Map<?, ?> rawOwnerMap)) {
            return new StorageData(memories);
        }

        for (Map.Entry<?, ?> ownerEntry : rawOwnerMap.entrySet()) {
            List<LongTermMemoryEntry> ownerMemories = new ArrayList<>();
            if (ownerEntry.getValue() instanceof List<?> rawEntries) {
                for (Object rawEntry : rawEntries) {
                    LongTermMemoryEntry entry = objectMapper.convertValue(rawEntry, LongTermMemoryEntry.class);
                    if (entry != null && entry.getId() != null && !entry.getId().isBlank()) {
                        ownerMemories.add(entry);
                    }
                }
            }
            memories.put(String.valueOf(ownerEntry.getKey()), ownerMemories);
        }
        return new StorageData(memories);
    }

    private Path storagePath() {
        return Path.of(memoryProperties.getLongTerm().getStoragePath());
    }

    private record StorageData(Map<String, List<LongTermMemoryEntry>> memories) {
        static StorageData empty() {
            return new StorageData(new LinkedHashMap<>());
        }
    }
}

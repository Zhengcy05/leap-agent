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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于本地 JSON 文件的偏好仓储。
 */
@Repository
public class FilePreferenceRepository implements PreferenceRepository {

    private static final Logger logger = LoggerFactory.getLogger(FilePreferenceRepository.class);
    private static final TypeReference<Map<String, Map<String, String>>> STORAGE_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final MemoryProperties memoryProperties;

    public FilePreferenceRepository(ObjectMapper objectMapper, MemoryProperties memoryProperties) {
        this.objectMapper = objectMapper;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public synchronized void save(String ownerId, String key, String value) {
        // 文件结构固定为 { ownerId -> { key -> value } }，后续切数据库时可以保留 owner 维度。
        Map<String, Map<String, String>> data = readStorage();
        data.computeIfAbsent(ownerId, ignored -> new LinkedHashMap<>()).put(key, value);
        writeStorage(data);
    }

    @Override
    public synchronized Map<String, String> loadAll(String ownerId) {
        Map<String, Map<String, String>> data = readStorage();
        Map<String, String> values = data.get(ownerId);
        if (values == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(values);
    }

    private Map<String, Map<String, String>> readStorage() {
        Path path = storagePath();
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }

        try {
            return objectMapper.readValue(path.toFile(), STORAGE_TYPE);
        } catch (IOException e) {
            logger.warn("读取偏好文件失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void writeStorage(Map<String, Map<String, String>> data) {
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
}

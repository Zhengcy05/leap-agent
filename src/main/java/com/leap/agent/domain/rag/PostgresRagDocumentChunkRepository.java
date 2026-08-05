package com.leap.agent.domain.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * PostgreSQL RAG chunk 仓储。
 *
 * <p>RAG chunk 原文和 metadata 以 PostgreSQL 为事实源；Milvus 只保存向量索引，
 * 检索命中后必须回表 hydrate，避免索引陈旧时把已删除/旧版本内容注入 prompt。</p>
 */
@Repository
public class PostgresRagDocumentChunkRepository implements RagDocumentChunkRepository {

    private static final Logger logger = LoggerFactory.getLogger(PostgresRagDocumentChunkRepository.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private volatile boolean schemaInitialized;

    public PostgresRagDocumentChunkRepository(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void markSourceDeleted(String source) {
        ensureSchema();
        String sql = "UPDATE " + tableName() + " SET status = 'DELETED', updated_at = ? WHERE source = ? AND status <> 'DELETED'";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, source);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.warn("标记 RAG 旧分片删除失败: {}", e.getMessage());
        }
    }

    @Override
    public synchronized RagDocumentChunk save(RagDocumentChunk chunk) {
        ensureSchema();
        // version 在 PG 侧递增，索引层只复制它。这样 Milvus/ES 以后做 reconcile 时，
        // 可以用 version/content_hash 判断投影是否落后于事实源。
        String sql = """
                INSERT INTO %s (
                    id, source, file_name, extension, chunk_index, total_chunks, title,
                    content, metadata_json, content_hash, version, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    source = EXCLUDED.source,
                    file_name = EXCLUDED.file_name,
                    extension = EXCLUDED.extension,
                    chunk_index = EXCLUDED.chunk_index,
                    total_chunks = EXCLUDED.total_chunks,
                    title = EXCLUDED.title,
                    content = EXCLUDED.content,
                    metadata_json = EXCLUDED.metadata_json,
                    content_hash = EXCLUDED.content_hash,
                    version = %s.version + 1,
                    status = EXCLUDED.status,
                    updated_at = EXCLUDED.updated_at
                RETURNING version, created_at, updated_at
                """.formatted(tableName(), tableName());

        long now = System.currentTimeMillis();
        if (chunk.getCreatedAt() <= 0) {
            chunk.setCreatedAt(now);
        }
        chunk.setUpdatedAt(now);
        if (chunk.getVersion() <= 0) {
            chunk.setVersion(1L);
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, chunk.getId());
            statement.setString(2, chunk.getSource());
            statement.setString(3, chunk.getFileName());
            statement.setString(4, chunk.getExtension());
            statement.setInt(5, chunk.getChunkIndex());
            statement.setInt(6, chunk.getTotalChunks());
            statement.setString(7, chunk.getTitle());
            statement.setString(8, chunk.getContent());
            statement.setString(9, objectMapper.writeValueAsString(chunk.getMetadata()));
            statement.setString(10, chunk.getContentHash());
            statement.setLong(11, chunk.getVersion());
            statement.setString(12, chunk.getStatus());
            statement.setLong(13, chunk.getCreatedAt());
            statement.setLong(14, chunk.getUpdatedAt());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    chunk.setVersion(resultSet.getLong("version"));
                    chunk.setCreatedAt(resultSet.getLong("created_at"));
                    chunk.setUpdatedAt(resultSet.getLong("updated_at"));
                }
            }
        } catch (Exception e) {
            logger.warn("保存 RAG 分片失败: {}", e.getMessage());
        }
        return chunk;
    }

    @Override
    public synchronized List<RagDocumentChunk> findActiveByIds(List<String> ids) {
        ensureSchema();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        String sql = """
                SELECT id, source, file_name, extension, chunk_index, total_chunks, title,
                       content, metadata_json::TEXT AS metadata_json, content_hash, version, status, created_at, updated_at
                FROM %s
                WHERE status = 'ACTIVE' AND id IN (%s)
                """.formatted(tableName(), placeholders);

        // SQL 的 IN 不保证返回顺序；先按 id 放进 map，再按召回排序的 ids 重组结果。
        Map<String, RagDocumentChunk> byId = new LinkedHashMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                statement.setString(i + 1, ids.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    RagDocumentChunk chunk = mapChunk(resultSet);
                    byId.put(chunk.getId(), chunk);
                }
            }
        } catch (SQLException e) {
            logger.warn("按 ID 读取 RAG 分片失败: {}", e.getMessage());
        }

        List<RagDocumentChunk> ordered = new ArrayList<>();
        for (String id : ids) {
            RagDocumentChunk chunk = byId.get(id);
            if (chunk != null) {
                ordered.add(chunk);
            }
        }
        return ordered;
    }

    private void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        if (!ragProperties.getPostgres().isInitSchema()) {
            schemaInitialized = true;
            return;
        }
        String tableName = tableName();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            // 这里是轻量内置 schema 初始化，便于本地开发；生产可以关闭 init-schema，
            // 改由 Flyway/Liquibase 等迁移工具管理表结构。
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id TEXT PRIMARY KEY,
                        source TEXT NOT NULL,
                        file_name TEXT,
                        extension TEXT,
                        chunk_index INTEGER NOT NULL,
                        total_chunks INTEGER NOT NULL,
                        title TEXT,
                        content TEXT NOT NULL,
                        metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                        content_hash TEXT NOT NULL,
                        version BIGINT NOT NULL DEFAULT 1,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.formatted(tableName));
            statement.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_source ON " + tableName + " (source)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_status ON " + tableName + " (status)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_hash ON " + tableName + " (content_hash)");
            schemaInitialized = true;
        } catch (SQLException e) {
            logger.warn("初始化 RAG 分片表失败: {}", e.getMessage());
        }
    }

    private RagDocumentChunk mapChunk(ResultSet resultSet) throws SQLException {
        RagDocumentChunk chunk = new RagDocumentChunk();
        chunk.setId(resultSet.getString("id"));
        chunk.setSource(resultSet.getString("source"));
        chunk.setFileName(resultSet.getString("file_name"));
        chunk.setExtension(resultSet.getString("extension"));
        chunk.setChunkIndex(resultSet.getInt("chunk_index"));
        chunk.setTotalChunks(resultSet.getInt("total_chunks"));
        chunk.setTitle(resultSet.getString("title"));
        chunk.setContent(resultSet.getString("content"));
        chunk.setMetadata(readMetadata(resultSet.getString("metadata_json")));
        chunk.setContentHash(resultSet.getString("content_hash"));
        chunk.setVersion(resultSet.getLong("version"));
        chunk.setStatus(resultSet.getString("status"));
        chunk.setCreatedAt(resultSet.getLong("created_at"));
        chunk.setUpdatedAt(resultSet.getLong("updated_at"));
        return chunk;
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Connection openConnection() throws SQLException {
        RagProperties.Postgres postgres = ragProperties.getPostgres();
        Properties properties = new Properties();
        properties.setProperty("user", postgres.getUsername());
        properties.setProperty("password", postgres.getPassword());
        return DriverManager.getConnection(postgres.getJdbcUrl(), properties);
    }

    private String tableName() {
        String configured = ragProperties.getPostgres().getChunkTableName();
        if (configured == null || !configured.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return "rag_document_chunk";
        }
        return configured;
    }
}

package com.leap.agent.domain.memory.longterm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL 长期记忆仓储。
 *
 * <p>PostgreSQL 作为长期记忆事实源，保存完整条目、治理字段和 embedding；
 * Milvus 后续只适合作为可选向量索引层，不承担审计、合并、过期等事务型状态。</p>
 */
@Repository
@ConditionalOnProperty(prefix = "memory.long-term", name = "repository", havingValue = "postgres")
public class PostgresLongTermMemoryRepository implements LongTermMemoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(PostgresLongTermMemoryRepository.class);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Float>> FLOAT_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final MemoryProperties memoryProperties;
    private volatile boolean schemaInitialized;
    private volatile boolean schemaAvailable = true;

    public PostgresLongTermMemoryRepository(ObjectMapper objectMapper, MemoryProperties memoryProperties) {
        this.objectMapper = objectMapper;
        this.memoryProperties = memoryProperties;
    }

    @Override
    public synchronized List<LongTermMemoryEntry> loadAll(String ownerId) {
        ensureSchema();
        if (!schemaAvailable) {
            return List.of();
        }
        String sql = """
                SELECT id, session_id, category, content, tags_json::TEXT AS tags_json, importance, confidence,
                       memory_source, embedding_json::TEXT AS embedding_json, created_at, last_accessed, version, status
                FROM %s
                WHERE owner_id = ?
                ORDER BY created_at, id
                """.formatted(tableName());

        List<LongTermMemoryEntry> entries = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    LongTermMemoryEntry entry = mapEntry(resultSet);
                    if (entry.getId() != null && !entry.getId().isBlank()) {
                        entries.add(entry);
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("读取 PostgreSQL 长期记忆失败: {}", e.getMessage());
        }
        return entries;
    }

    @Override
    public synchronized void saveAll(String ownerId, List<LongTermMemoryEntry> entries) {
        ensureSchema();
        if (!schemaAvailable) {
            return;
        }
        String upsertSql = """
                INSERT INTO %s (
                    id, owner_id, session_id, category, content, tags_json, importance, confidence,
                    memory_source, embedding_json, created_at, last_accessed, version, status
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (owner_id, id) DO UPDATE SET
                    session_id = EXCLUDED.session_id,
                    category = EXCLUDED.category,
                    content = EXCLUDED.content,
                    tags_json = EXCLUDED.tags_json,
                    importance = EXCLUDED.importance,
                    confidence = EXCLUDED.confidence,
                    memory_source = EXCLUDED.memory_source,
                    embedding_json = EXCLUDED.embedding_json,
                    created_at = EXCLUDED.created_at,
                    last_accessed = EXCLUDED.last_accessed,
                    version = EXCLUDED.version,
                    status = EXCLUDED.status
                """.formatted(tableName());

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(upsertSql)) {
                    for (LongTermMemoryEntry entry : entries) {
                        bindEntry(statement, ownerId, entry);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                deleteMissing(connection, ownerId, entries);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            logger.warn("写入 PostgreSQL 长期记忆失败: {}", e.getMessage());
        }
    }

    private void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        if (!memoryProperties.getLongTerm().getPostgres().isInitSchema()) {
            schemaInitialized = true;
            return;
        }

        String tableName = tableName();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        session_id TEXT,
                        category TEXT NOT NULL DEFAULT 'general',
                        content TEXT NOT NULL,
                        tags_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                        importance DOUBLE PRECISION NOT NULL DEFAULT 0,
                        confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
                        memory_source TEXT NOT NULL DEFAULT 'LEGACY',
                        embedding_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                        created_at BIGINT NOT NULL,
                        last_accessed BIGINT NOT NULL,
                        version BIGINT NOT NULL DEFAULT 1,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        PRIMARY KEY (owner_id, id)
                    )
                    """.formatted(tableName));
            statement.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_owner_category ON " + tableName + " (owner_id, category)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_owner_status ON " + tableName + " (owner_id, status)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_last_accessed ON " + tableName + " (last_accessed)");
            schemaAvailable = true;
            schemaInitialized = true;
        } catch (SQLException e) {
            schemaAvailable = false;
            schemaInitialized = true;
            logger.warn("初始化 PostgreSQL 长期记忆表失败: {}", e.getMessage());
        }
    }

    private Connection openConnection() throws SQLException {
        MemoryProperties.LongTerm.Postgres postgres = memoryProperties.getLongTerm().getPostgres();
        Properties properties = new Properties();
        properties.setProperty("user", postgres.getUsername());
        properties.setProperty("password", postgres.getPassword());
        return DriverManager.getConnection(postgres.getJdbcUrl(), properties);
    }

    private void bindEntry(PreparedStatement statement, String ownerId, LongTermMemoryEntry entry) throws Exception {
        statement.setString(1, entry.getId());
        statement.setString(2, ownerId);
        statement.setString(3, blankToNull(entry.getSessionId()));
        statement.setString(4, entry.getCategory());
        statement.setString(5, entry.getContent());
        statement.setString(6, objectMapper.writeValueAsString(entry.getTags()));
        statement.setDouble(7, entry.getImportance());
        statement.setDouble(8, entry.getConfidence());
        statement.setString(9, entry.getSource() != null ? entry.getSource().name() : LongTermMemorySource.LEGACY.name());
        statement.setString(10, objectMapper.writeValueAsString(entry.getEmbedding()));
        statement.setLong(11, entry.getCreatedAt());
        statement.setLong(12, entry.getLastAccessed());
        statement.setLong(13, entry.getVersion());
        statement.setString(14, entry.getStatus() != null ? entry.getStatus().name() : LongTermMemoryStatus.ACTIVE.name());
    }

    private LongTermMemoryEntry mapEntry(ResultSet resultSet) throws SQLException {
        LongTermMemoryEntry entry = new LongTermMemoryEntry();
        entry.setId(resultSet.getString("id"));
        entry.setSessionId(resultSet.getString("session_id"));
        entry.setCategory(resultSet.getString("category"));
        entry.setContent(resultSet.getString("content"));
        entry.setTags(readList(resultSet.getString("tags_json"), STRING_LIST_TYPE));
        entry.setImportance(resultSet.getDouble("importance"));
        entry.setConfidence(resultSet.getDouble("confidence"));
        entry.setSource(parseSource(resultSet.getString("memory_source")));
        entry.setEmbedding(readList(resultSet.getString("embedding_json"), FLOAT_LIST_TYPE));
        entry.setCreatedAt(resultSet.getLong("created_at"));
        entry.setLastAccessed(resultSet.getLong("last_accessed"));
        entry.setVersion(resultSet.getLong("version"));
        entry.setStatus(parseStatus(resultSet.getString("status")));
        return entry;
    }

    private void deleteMissing(Connection connection, String ownerId, List<LongTermMemoryEntry> entries) throws SQLException {
        if (entries.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + tableName() + " WHERE owner_id = ?")) {
                statement.setString(1, ownerId);
                statement.executeUpdate();
            }
            return;
        }

        Set<String> ids = entries.stream()
                .map(LongTermMemoryEntry::getId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        String sql = "DELETE FROM " + tableName() + " WHERE owner_id = ? AND id NOT IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId);
            int index = 2;
            for (String id : ids) {
                statement.setString(index++, id);
            }
            statement.executeUpdate();
        }
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> typeReference) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            return List.of();
        }
    }

    private LongTermMemorySource parseSource(String value) {
        return LongTermMemorySource.fromName(value).orElse(LongTermMemorySource.LEGACY);
    }

    private LongTermMemoryStatus parseStatus(String value) {
        return LongTermMemoryStatus.fromName(value).orElse(LongTermMemoryStatus.ACTIVE);
    }

    private String tableName() {
        String configured = memoryProperties.getLongTerm().getPostgres().getTableName();
        if (configured == null || !configured.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return "long_term_memory";
        }
        return configured;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

}

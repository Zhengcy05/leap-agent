package com.leap.agent.domain.memory.longterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.MemoryProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Neo4j 长期记忆图投影客户端。
 *
 * <p>PostgreSQL 仍是长期记忆事实源；Neo4j 只保存可重建的 Memory 节点和关系边。
 * 因此这里所有操作都是 best-effort：图不可用时直接降级，不影响长期记忆写入和召回。</p>
 */
@Service
public class Neo4jMemoryGraphClient {

    private static final Logger logger = LoggerFactory.getLogger(Neo4jMemoryGraphClient.class);
    private static final int MAX_NEIGHBOR_HOPS = 3;

    private final MemoryProperties memoryProperties;
    private final ObjectMapper objectMapper;
    private Driver driver;
    private volatile boolean available;

    /**
     * 注入记忆配置和 JSON 序列化器。
     */
    public Neo4jMemoryGraphClient(MemoryProperties memoryProperties, ObjectMapper objectMapper) {
        this.memoryProperties = memoryProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时按配置连接 Neo4j，失败时标记为不可用并降级。
     */
    @PostConstruct
    public void initialize() {
        MemoryProperties.LongTerm.Graph graph = graphProperties();
        if (!graph.isEnabled()) {
            logger.info("Neo4j 长期记忆图未启用");
            return;
        }
        try {
            driver = GraphDatabase.driver(
                    graph.getUri(),
                    AuthTokens.basic(graph.getUsername(), graph.getPassword()));
            driver.verifyConnectivity();
            available = true;
            if (graph.isInitSchema()) {
                ensureSchema();
            }
            logger.info("Neo4j 长期记忆图已连接: {}", graph.getUri());
        } catch (Exception e) {
            available = false;
            closeDriver();
            logger.warn("Neo4j 长期记忆图不可用，降级为纯长期记忆: {}", e.getMessage());
        }
    }

    /**
     * 应用退出时关闭 Neo4j driver。
     */
    @PreDestroy
    public void shutdown() {
        closeDriver();
    }

    /**
     * 判断当前图投影是否可以读写。
     */
    public boolean isAvailable() {
        return available && driver != null;
    }

    /**
     * 将长期记忆条目投影成 Neo4j Memory 节点。
     */
    public void upsertMemory(String ownerId, LongTermMemoryEntry entry) {
        if (!isAvailable() || entry == null || entry.getId() == null || entry.getId().isBlank()) {
            return;
        }
        try {
            String tagsJson = objectMapper.writeValueAsString(entry.getTags());
            write(session -> {
                session.run("""
                        MERGE (m:Memory {key: $key})
                        SET m.owner_id = $ownerId,
                            m.memory_id = $memoryId,
                            m.session_id = $sessionId,
                            m.category = $category,
                            m.content = $content,
                            m.tags_json = $tagsJson,
                            m.importance = $importance,
                            m.confidence = $confidence,
                            m.version = $version,
                            m.status = $status,
                            m.created_at = $createdAt,
                            m.last_accessed = $lastAccessed
                        """, Values.parameters(
                        "key", memoryKey(ownerId, entry.getId()),
                        "ownerId", ownerId,
                        "memoryId", entry.getId(),
                        "sessionId", entry.getSessionId(),
                        "category", entry.getCategory(),
                        "content", entry.getContent(),
                        "tagsJson", tagsJson,
                        "importance", entry.getImportance(),
                        "confidence", entry.getConfidence(),
                        "version", entry.getVersion(),
                        "status", entry.getStatus() != null ? entry.getStatus().name() : LongTermMemoryStatus.ACTIVE.name(),
                        "createdAt", entry.getCreatedAt(),
                        "lastAccessed", entry.getLastAccessed())).consume();
                return null;
            });
        } catch (Exception e) {
            logger.debug("Neo4j upsert Memory 节点失败: {}", e.getMessage());
        }
    }

    /**
     * 建立上一条记忆到当前记忆的时序边。
     */
    public void addFollows(String ownerId, String fromMemoryId, String toMemoryId) {
        addMemoryEdge(ownerId, fromMemoryId, toMemoryId, "FOLLOWS", 1.0D);
    }

    /**
     * 建立两条语义相似记忆之间的关联边。
     */
    public void addSimilar(String ownerId, String fromMemoryId, String toMemoryId, double weight) {
        addMemoryEdge(ownerId, fromMemoryId, toMemoryId, "SIMILAR_TO", weight);
    }

    /**
     * 从召回 seed 出发，沿图关系扩展关联记忆 ID。
     */
    public List<String> expandMemoryIds(String ownerId, List<String> seedIds, int hops) {
        if (!isAvailable() || seedIds == null || seedIds.isEmpty()) {
            return List.of();
        }
        int boundedHops = Math.max(1, Math.min(hops, MAX_NEIGHBOR_HOPS));
        String query = """
                MATCH (m:Memory)
                WHERE m.owner_id = $ownerId AND m.memory_id IN $ids
                MATCH (m)-[:FOLLOWS|SIMILAR_TO|CAUSES|BELONGS_TO*1..%d]-(n:Memory)
                WHERE n.owner_id = $ownerId
                  AND NOT n.memory_id IN $ids
                  AND coalesce(n.status, 'ACTIVE') = 'ACTIVE'
                RETURN DISTINCT n.memory_id AS id
                """.formatted(boundedHops);
        try {
            return read(session -> {
                List<String> ids = new ArrayList<>();
                var result = session.run(query, Values.parameters("ownerId", ownerId, "ids", seedIds));
                while (result.hasNext()) {
                    ids.add(result.next().get("id").asString());
                }
                return ids;
            });
        } catch (Exception e) {
            logger.debug("Neo4j 扩展长期记忆邻居失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 找出入度较高、过期淘汰时应被保护的记忆节点。
     */
    public Set<String> findHighCentralityMemoryIds(String ownerId, List<String> candidateIds, int threshold) {
        if (!isAvailable() || candidateIds == null || candidateIds.isEmpty() || threshold <= 0) {
            return Set.of();
        }
        try {
            return read(session -> {
                Set<String> ids = new LinkedHashSet<>();
                var result = session.run("""
                        MATCH (m:Memory)
                        WHERE m.owner_id = $ownerId AND m.memory_id IN $ids
                        WITH m, size([(m)<-[]-() | 1]) AS indegree
                        WHERE indegree >= $threshold
                        RETURN m.memory_id AS id
                        """, Values.parameters("ownerId", ownerId, "ids", candidateIds, "threshold", threshold));
                while (result.hasNext()) {
                    ids.add(result.next().get("id").asString());
                }
                return ids;
            });
        } catch (Exception e) {
            logger.debug("Neo4j 查询高中心度长期记忆失败: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * 删除某条记忆的图节点和所有关联边。
     */
    public void deleteMemory(String ownerId, String memoryId) {
        if (!isAvailable() || memoryId == null || memoryId.isBlank()) {
            return;
        }
        try {
            write(session -> {
                session.run("""
                        MATCH (m:Memory {key: $key})
                        DETACH DELETE m
                        """, Values.parameters("key", memoryKey(ownerId, memoryId))).consume();
                return null;
            });
        } catch (Exception e) {
            logger.debug("Neo4j 删除长期记忆节点失败: {}", e.getMessage());
        }
    }

    /**
     * 读取调试接口需要展示的图连接状态和度数。
     */
    public Map<String, GraphStats> readStats(String ownerId, List<String> memoryIds) {
        if (!isAvailable() || memoryIds == null || memoryIds.isEmpty()) {
            return Map.of();
        }
        try {
            return read(session -> {
                Map<String, GraphStats> stats = new HashMap<>();
                var result = session.run("""
                        MATCH (m:Memory)
                        WHERE m.owner_id = $ownerId AND m.memory_id IN $ids
                        RETURN m.memory_id AS id,
                               size([(m)<-[]-() | 1]) AS in_degree,
                               size([(m)-[]->() | 1]) AS out_degree
                        """, Values.parameters("ownerId", ownerId, "ids", memoryIds));
                while (result.hasNext()) {
                    Record record = result.next();
                    String id = record.get("id").asString();
                    long inDegree = record.get("in_degree").asLong(0L);
                    long outDegree = record.get("out_degree").asLong(0L);
                    stats.put(id, new GraphStats(true, inDegree, outDegree));
                }
                return stats;
            });
        } catch (Exception e) {
            logger.debug("Neo4j 读取长期记忆图统计失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 幂等初始化 Neo4j 约束和查询索引。
     */
    private void ensureSchema() {
        try {
            write(session -> {
                session.run("CREATE CONSTRAINT memory_key IF NOT EXISTS FOR (m:Memory) REQUIRE m.key IS UNIQUE").consume();
                session.run("CREATE INDEX memory_owner IF NOT EXISTS FOR (m:Memory) ON (m.owner_id)").consume();
                session.run("CREATE INDEX memory_owner_status IF NOT EXISTS FOR (m:Memory) ON (m.owner_id, m.status)").consume();
                session.run("CREATE INDEX memory_category IF NOT EXISTS FOR (m:Memory) ON (m.category)").consume();
                return null;
            });
        } catch (Exception e) {
            available = false;
            logger.warn("Neo4j 长期记忆图 schema 初始化失败，后续图增强将跳过: {}", e.getMessage());
        }
    }

    /**
     * 按受控的边类型创建记忆关系，避免动态 Cypher 注入。
     */
    private void addMemoryEdge(String ownerId, String fromMemoryId, String toMemoryId, String edgeType, double weight) {
        if (!isAvailable()
                || fromMemoryId == null || fromMemoryId.isBlank()
                || toMemoryId == null || toMemoryId.isBlank()
                || fromMemoryId.equals(toMemoryId)) {
            return;
        }
        String query = switch (edgeType) {
            case "FOLLOWS" -> """
                    MATCH (a:Memory {key: $fromKey}), (b:Memory {key: $toKey})
                    MERGE (a)-[r:FOLLOWS]->(b)
                    SET r.weight = $weight, r.updated_at = $updatedAt
                    """;
            case "SIMILAR_TO" -> """
                    MATCH (a:Memory {key: $fromKey}), (b:Memory {key: $toKey})
                    MERGE (a)-[r:SIMILAR_TO]->(b)
                    SET r.weight = $weight, r.updated_at = $updatedAt
                    """;
            default -> null;
        };
        if (query == null) {
            return;
        }
        try {
            write(session -> {
                session.run(query, Values.parameters(
                        "fromKey", memoryKey(ownerId, fromMemoryId),
                        "toKey", memoryKey(ownerId, toMemoryId),
                        "weight", weight,
                        "updatedAt", System.currentTimeMillis())).consume();
                return null;
            });
        } catch (Exception e) {
            logger.debug("Neo4j 建立长期记忆边失败: {}", e.getMessage());
        }
    }

    /**
     * 使用只读 session 执行一次 Neo4j 查询。
     */
    private <T> T read(SessionCallback<T> callback) {
        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(AccessMode.READ)
                .build())) {
            return callback.apply(session);
        }
    }

    /**
     * 使用写 session 执行一次 Neo4j 更新。
     */
    private <T> T write(SessionCallback<T> callback) {
        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(AccessMode.WRITE)
                .build())) {
            return callback.apply(session);
        }
    }

    /**
     * 生成图节点唯一 key，隔离不同 owner 下的同名记忆。
     */
    private String memoryKey(String ownerId, String memoryId) {
        return ownerId + ":" + memoryId;
    }

    /**
     * 获取长期记忆图配置块。
     */
    private MemoryProperties.LongTerm.Graph graphProperties() {
        return memoryProperties.getLongTerm().getGraph();
    }

    /**
     * 安静关闭 Neo4j driver，避免退出流程被关闭异常打断。
     */
    private void closeDriver() {
        if (driver != null) {
            try {
                driver.close();
            } catch (Exception ignored) {
                // 关闭失败不影响应用退出。
            }
            driver = null;
        }
    }

    @FunctionalInterface
    private interface SessionCallback<T> {
        /**
         * 在指定 session 中执行 Neo4j 操作。
         */
        T apply(Session session);
    }

    /**
     * 记忆图调试统计信息。
     */
    public record GraphStats(boolean linked, long inDegree, long outDegree) {
    }
}

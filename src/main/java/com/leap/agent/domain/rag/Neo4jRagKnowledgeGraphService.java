package com.leap.agent.domain.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.RagProperties;
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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RAG 专用 Neo4j Knowledge Graph 投影服务。
 *
 * <p>PostgreSQL 仍是 chunk 事实源；KG 只保存可重建的实体、关系和 chunk 映射。
 * 图不可用、LLM 抽取失败或 JSON 解析失败时都只降级跳过，不阻塞 RAG 主链路。</p>
 */
@Service
public class Neo4jRagKnowledgeGraphService {

    private static final Logger logger = LoggerFactory.getLogger(Neo4jRagKnowledgeGraphService.class);
    private static final int MAX_HOPS = 3;
    private static final Set<String> VALID_ENTITY_TYPES = Set.of(
            "Person",        // 人物
            "Organization",  // 组织机构、公司、团队
            "Location",      // 地点、地域
            "Concept",       // 概念、技术名词、专业术语（适配你程序员知识库场景）
            "Event",         // 事件、技术发布会、事故、活动
            "Product",       // 产品、框架、软件、组件
            "Unknown");      // 无法识别的实体统一标记为未知

    private static final Set<String> VALID_RELATION_TYPES = Set.of(
            "RELATES_TO",    // 存在关联、相关
            "PART_OF",       // 属于整体的一部分（例如：SpringBoot PART_OF Spring生态）
            "CAUSES",        // 导致、引发（报错原因、技术问题诱因）
            "DESCRIBES",     // 描述、讲解某事物
            "MENTIONS",      // 提及、引用
            "WORKS_FOR",     // 任职、供职于某组织
            "LOCATED_IN");   // 坐落于、位于某地


    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rag-kg-indexer");
        thread.setDaemon(true);
        return thread;
    });
    private Driver driver;
    private volatile boolean available;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    public Neo4jRagKnowledgeGraphService(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时连接 Neo4j 并初始化 KG schema。
     */
    @PostConstruct
    public void initialize() {
        RagProperties.KnowledgeGraph kg = kgProperties();
        if (!kg.isEnabled()) {
            logger.info("RAG Neo4j Knowledge Graph 未启用");
            return;
        }
        try {
            driver = GraphDatabase.driver(kg.getUri(), AuthTokens.basic(kg.getUsername(), kg.getPassword()));
            driver.verifyConnectivity();
            available = true;
            if (kg.isInitSchema()) {
                ensureSchema();
            }
            logger.info("RAG Neo4j Knowledge Graph 已连接: {}", kg.getUri());
        } catch (Exception e) {
            available = false;
            closeDriver();
            logger.warn("RAG Neo4j Knowledge Graph 不可用，RAG 降级为 Milvus/ES 双路召回: {}", e.getMessage());
        }
    }

    /**
     * 应用退出时关闭异步索引线程和 Neo4j driver。
     */
    @PreDestroy
    public void shutdown() {
        indexExecutor.shutdown();
        closeDriver();
    }

    /**
     * 判断 KG 投影当前是否可读写。
     */
    public boolean isAvailable() {
        return available && driver != null;
    }

    /**
     * 异步将已持久化 chunk 抽取成实体关系并写入 Neo4j。
     */
    public void indexChunkAsync(RagDocumentChunk chunk) {
        if (!shouldIndex(chunk)) {
            return;
        }
        if (!kgProperties().isAsyncIndexEnabled()) {
            indexChunk(chunk);
            return;
        }
        indexExecutor.execute(() -> {
            try {
                indexChunk(chunk);
            } catch (Exception e) {
                logger.debug("RAG KG 异步索引失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 删除某个 source 对应的 KG chunk 和关系投影。
     */
    public void deleteBySource(String source) {
        if (!isAvailable() || source == null || source.isBlank()) {
            return;
        }
        try {
            write(session -> {
                session.run("""
                        MATCH (c:KgChunk {source: $source})
                        DETACH DELETE c
                        """, Values.parameters("source", source)).consume();
                session.run("""
                        MATCH ()-[r]-()
                        WHERE r.source = $source
                        DELETE r
                        """, Values.parameters("source", source)).consume();
                session.run("""
                        MATCH (e:KgEntity)
                        WHERE NOT (e)--()
                        DELETE e
                        """).consume();
                return null;
            });
        } catch (Exception e) {
            logger.debug("RAG KG 删除 source 投影失败: {}", e.getMessage());
        }
    }

    /**
     * 从查询文本抽实体并沿 KG 找到相关 chunk 候选。
     */
    public List<GraphHit> searchGraph(String queryText, int topK) {
        if (!isAvailable() || queryText == null || queryText.isBlank()) {
            return List.of();
        }
        ExtractResult extracted = extract(queryText);
        if (extracted.entities().isEmpty()) {
            return List.of();
        }
        List<String> entityKeys = extracted.entities().stream()
                .map(entity -> entityKey(entity.name()))
                .distinct()
                .toList();
        int hops = Math.max(1, Math.min(kgProperties().getMaxHops(), MAX_HOPS));
        int limit = Math.max(1, topK);
        String query = """
                MATCH (seed:KgEntity)
                WHERE seed.key IN $keys
                MATCH path = (seed)-[:RELATES_TO|PART_OF|CAUSES|DESCRIBES|MENTIONS|WORKS_FOR|LOCATED_IN*0..%d]-(neighbor:KgEntity)
                MATCH (neighbor)-[m:MENTIONED_IN]->(chunk:KgChunk)
                WHERE coalesce(chunk.status, 'ACTIVE') = 'ACTIVE'
                WITH chunk,
                     collect(DISTINCT seed.name) AS seeds,
                     collect(DISTINCT neighbor.name) AS neighbors,
                     min(length(path)) AS min_hop,
                     count(DISTINCT neighbor) AS entity_count
                RETURN chunk.id AS chunk_id,
                       seeds,
                       neighbors,
                       min_hop,
                       entity_count
                ORDER BY size(seeds) DESC, entity_count DESC, min_hop ASC
                LIMIT $limit
                """.formatted(hops);
        try {
            return read(session -> {
                List<GraphHit> hits = new ArrayList<>();
                var result = session.run(query, Values.parameters("keys", entityKeys, "limit", limit));
                while (result.hasNext()) {
                    Record record = result.next();
                    String chunkId = record.get("chunk_id").asString("");
                    if (chunkId.isBlank()) {
                        continue;
                    }
                    int minHop = record.get("min_hop").asInt(0);
                    int entityCount = record.get("entity_count").asInt(1);
                    List<String> seeds = record.get("seeds").asList(value -> value.asString());
                    List<String> neighbors = record.get("neighbors").asList(value -> value.asString());
                    double score = seeds.size() * 0.60D + entityCount * 0.08D + 1D / (1D + Math.max(0, minHop));
                    hits.add(new GraphHit(chunkId, score, seeds, neighbors));
                }
                return hits;
            });
        } catch (Exception e) {
            logger.debug("RAG KG 图召回失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 同步执行单个 chunk 的实体关系抽取和写图。
     */
    private void indexChunk(RagDocumentChunk chunk) {
        if (!shouldIndex(chunk)) {
            return;
        }
        ExtractResult extracted = extract(chunk.getContent());
        if (extracted.entities().isEmpty()) {
            return;
        }
        try {
            write(session -> {
                upsertChunk(session, chunk);
                Map<String, Entity> entityMap = new LinkedHashMap<>();
                for (Entity entity : extracted.entities()) {
                    entityMap.put(entity.name(), entity);
                    upsertEntity(session, entity);
                    linkEntityToChunk(session, entity, chunk);
                }
                for (Relation relation : extracted.relations()) {
                    if (entityMap.containsKey(relation.from()) && entityMap.containsKey(relation.to())) {
                        upsertRelation(session, relation, chunk);
                    }
                }
                return null;
            });
            logger.debug("RAG KG chunk 索引完成: {}", chunk.getId());
        } catch (Exception e) {
            logger.debug("RAG KG chunk 写图失败: {}", e.getMessage());
        }
    }

    /**
     * 调用 LLM 从文本中抽取实体和关系。
     */
    private ExtractResult extract(String text) {
        if (!hasValidApiKey() || text == null || text.isBlank()) {
            return ExtractResult.empty();
        }
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(0.1)
                        .withMaxToken(1000)
                        .withTopP(0.8)
                        .build())
                .build();
        String prompt = """
                你是一个信息抽取专家。从给定文本中抽取命名实体和实体间关系。
                必须只输出 JSON 对象，不要解释，不要 markdown 代码块。

                实体类型 type 只允许：
                Person, Organization, Location, Concept, Event, Product, Unknown

                关系类型 rel_type 只允许：
                RELATES_TO, PART_OF, CAUSES, DESCRIBES, MENTIONS, WORKS_FOR, LOCATED_IN

                输出格式：
                {
                  "entities": [{"name":"实体名","type":"Concept"}],
                  "relations": [{"from":"实体A","to":"实体B","rel_type":"RELATES_TO"}]
                }

                如果没有可抽取实体，输出 {"entities":[],"relations":[]}

                文本：
                """ + safe(text);
        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return ExtractResult.empty();
            }
            return parseExtractResult(response.getResult().getOutput().getText());
        } catch (Exception e) {
            logger.debug("RAG KG LLM 抽取失败: {}", e.getMessage());
            return ExtractResult.empty();
        }
    }

    /**
     * 解析并清洗 LLM 抽取结果。
     */
    private ExtractResult parseExtractResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExtractResult.empty();
        }
        String cleaned = raw.trim().replace("```json", "").replace("```", "").trim();
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            List<Entity> entities = new ArrayList<>();
            Set<String> seenEntityNames = new LinkedHashSet<>();
            for (JsonNode entityNode : root.path("entities")) {
                String name = normalizeEntityName(entityNode.path("name").asText(""));
                if (name.isBlank() || !seenEntityNames.add(name)) {
                    continue;
                }
                String type = entityNode.path("type").asText("Unknown");
                if (!VALID_ENTITY_TYPES.contains(type)) {
                    type = "Unknown";
                }
                entities.add(new Entity(name, type));
            }

            List<Relation> relations = new ArrayList<>();
            for (JsonNode relationNode : root.path("relations")) {
                String from = normalizeEntityName(relationNode.path("from").asText(""));
                String to = normalizeEntityName(relationNode.path("to").asText(""));
                String relType = relationNode.path("rel_type").asText("RELATES_TO").toUpperCase(Locale.ROOT);
                if (from.isBlank() || to.isBlank() || from.equals(to)) {
                    continue;
                }
                if (!VALID_RELATION_TYPES.contains(relType)) {
                    relType = "RELATES_TO";
                }
                relations.add(new Relation(from, to, relType));
            }
            return new ExtractResult(entities, relations);
        } catch (Exception e) {
            logger.debug("RAG KG 抽取 JSON 解析失败: {}", e.getMessage());
            return ExtractResult.empty();
        }
    }

    /**
     * 幂等写入 chunk 节点。
     */
    private void upsertChunk(Session session, RagDocumentChunk chunk) {
        session.run("""
                MERGE (c:KgChunk {id: $id})
                SET c.source = $source,
                    c.file_name = $fileName,
                    c.title = $title,
                    c.chunk_index = $chunkIndex,
                    c.status = $status,
                    c.content_hash = $contentHash,
                    c.version = $version
                """, Values.parameters(
                "id", chunk.getId(),
                "source", chunk.getSource(),
                "fileName", chunk.getFileName(),
                "title", chunk.getTitle(),
                "chunkIndex", chunk.getChunkIndex(),
                "status", chunk.getStatus(),
                "contentHash", chunk.getContentHash(),
                "version", chunk.getVersion())).consume();
    }

    /**
     * 幂等写入实体节点。
     */
    private void upsertEntity(Session session, Entity entity) {
        session.run("""
                MERGE (e:KgEntity {key: $key})
                SET e.name = $name,
                    e.type = $type
                """, Values.parameters(
                "key", entityKey(entity.name()),
                "name", entity.name(),
                "type", entity.type())).consume();
    }

    /**
     * 建立实体到 chunk 的提及边。
     */
    private void linkEntityToChunk(Session session, Entity entity, RagDocumentChunk chunk) {
        session.run("""
                MATCH (e:KgEntity {key: $entityKey}), (c:KgChunk {id: $chunkId})
                MERGE (e)-[r:MENTIONED_IN {chunk_id: $chunkId}]->(c)
                SET r.source = $source,
                    r.weight = 1.0
                """, Values.parameters(
                "entityKey", entityKey(entity.name()),
                "chunkId", chunk.getId(),
                "source", chunk.getSource())).consume();
    }

    /**
     * 按白名单关系类型建立实体间关系。
     */
    private void upsertRelation(Session session, Relation relation, RagDocumentChunk chunk) {
        String query = switch (relation.relType()) {
            case "PART_OF" -> relationCypher("PART_OF");
            case "CAUSES" -> relationCypher("CAUSES");
            case "DESCRIBES" -> relationCypher("DESCRIBES");
            case "MENTIONS" -> relationCypher("MENTIONS");
            case "WORKS_FOR" -> relationCypher("WORKS_FOR");
            case "LOCATED_IN" -> relationCypher("LOCATED_IN");
            default -> relationCypher("RELATES_TO");
        };
        session.run(query, Values.parameters(
                "fromKey", entityKey(relation.from()),
                "toKey", entityKey(relation.to()),
                "source", chunk.getSource(),
                "chunkId", chunk.getId(),
                "weight", 1.0D)).consume();
    }

    /**
     * 构造受控关系类型的 Cypher。
     */
    private String relationCypher(String relType) {
        return """
                MATCH (a:KgEntity {key: $fromKey}), (b:KgEntity {key: $toKey})
                MERGE (a)-[r:%s {source: $source, chunk_id: $chunkId}]->(b)
                SET r.weight = $weight
                """.formatted(relType);
    }

    /**
     * 初始化 KG 约束和索引。
     */
    private void ensureSchema() {
        try {
            write(session -> {
                session.run("CREATE CONSTRAINT kg_entity_key IF NOT EXISTS FOR (e:KgEntity) REQUIRE e.key IS UNIQUE").consume();
                session.run("CREATE CONSTRAINT kg_chunk_id IF NOT EXISTS FOR (c:KgChunk) REQUIRE c.id IS UNIQUE").consume();
                session.run("CREATE INDEX kg_entity_name IF NOT EXISTS FOR (e:KgEntity) ON (e.name)").consume();
                session.run("CREATE INDEX kg_entity_type IF NOT EXISTS FOR (e:KgEntity) ON (e.type)").consume();
                session.run("CREATE INDEX kg_chunk_source IF NOT EXISTS FOR (c:KgChunk) ON (c.source)").consume();
                session.run("CREATE INDEX kg_chunk_status IF NOT EXISTS FOR (c:KgChunk) ON (c.status)").consume();
                return null;
            });
        } catch (Exception e) {
            available = false;
            logger.warn("RAG KG schema 初始化失败，后续 KG 召回将跳过: {}", e.getMessage());
        }
    }

    /**
     * 判断 chunk 是否可以提交 KG 投影。
     */
    private boolean shouldIndex(RagDocumentChunk chunk) {
        return isAvailable()
                && chunk != null
                && chunk.getId() != null
                && !chunk.getId().isBlank()
                && chunk.getContent() != null
                && !chunk.getContent().isBlank();
    }

    /**
     * 使用只读 session 执行 KG 查询。
     */
    private <T> T read(SessionCallback<T> callback) {
        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(AccessMode.READ)
                .build())) {
            return callback.apply(session);
        }
    }

    /**
     * 使用写 session 执行 KG 更新。
     */
    private <T> T write(SessionCallback<T> callback) {
        try (Session session = driver.session(SessionConfig.builder()
                .withDefaultAccessMode(AccessMode.WRITE)
                .build())) {
            return callback.apply(session);
        }
    }

    /**
     * 生成实体唯一 key。
     */
    private String entityKey(String name) {
        return normalizeEntityName(name).toLowerCase(Locale.ROOT);
    }

    /**
     * 清理实体名中的空白。
     */
    private String normalizeEntityName(String name) {
        return safe(name).replaceAll("\\s+", " ").trim();
    }

    /**
     * 获取 RAG KG 配置。
     */
    private RagProperties.KnowledgeGraph kgProperties() {
        return ragProperties.getKnowledgeGraph();
    }

    /**
     * 判断 DashScope API Key 是否有效。
     */
    private boolean hasValidApiKey() {
        return dashScopeApiKey != null
                && !dashScopeApiKey.isBlank()
                && !dashScopeApiKey.contains("your-api-key");
    }

    /**
     * 安静关闭 Neo4j driver。
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

    /**
     * 安全处理 null 字符串。
     */
    private String safe(String text) {
        return text == null ? "" : text;
    }

    @FunctionalInterface
    private interface SessionCallback<T> {
        /**
         * 在指定 session 中执行 Neo4j 操作。
         */
        T apply(Session session);
    }

    private record Entity(String name, String type) {
    }

    private record Relation(String from, String to, String relType) {
    }

    private record ExtractResult(List<Entity> entities, List<Relation> relations) {
        static ExtractResult empty() {
            return new ExtractResult(List.of(), List.of());
        }
    }

    public record GraphHit(String chunkId, double score, List<String> entities, List<String> hopPath) {
    }
}

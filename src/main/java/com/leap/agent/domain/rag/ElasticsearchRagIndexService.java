package com.leap.agent.domain.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.RagProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch RAG 关键词投影索引。
 *
 * <p>ES 只保存可重建的检索投影。命中结果必须回 PostgreSQL hydrate，
 * 不能直接把 ES 中的内容当事实源注入 prompt。</p>
 */
@Service
public class ElasticsearchRagIndexService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchRagIndexService.class);

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ElasticsearchRagIndexService(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(ragProperties.getElasticsearch().getTimeoutMillis()))
                .build();
    }

@PostConstruct
    public void ensureIndex() {
        if (!enabled()) {
            return;
        }
        try {
            Map<String, Object> mapping = Map.of(
                    "mappings", Map.of(
                            "properties", Map.of(
                                    "chunk_id", Map.of("type", "keyword"),
                                    "source", Map.of("type", "keyword"),
                                    "file_name", Map.of("type", "keyword"),
                                    // text 字段走 ES 默认 BM25 相关性；standard analyzer 能跑通，
                                    // 中文技术文档效果要继续优化时，应替换为 IK / smartcn 等中文 analyzer。
                                    "title", Map.of("type", "text", "analyzer", "standard"),
                                    "content", Map.of("type", "text", "analyzer", "standard"),
                                    "content_hash", Map.of("type", "keyword"),
                                    "version", Map.of("type", "long"),
                                    "status", Map.of("type", "keyword"),
                                    "updated_at", Map.of("type", "long")
                            )
                    )
            );
            HttpRequest request = requestBuilder(indexUri())
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(mapping)))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300 && response.statusCode() != 400) {
                logger.warn("初始化 ES RAG 索引失败: status={}, body={}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.warn("初始化 ES RAG 索引失败: {}", e.getMessage());
        }
    }

    public void indexChunk(RagDocumentChunk chunk) {
        if (!enabled() || chunk == null || chunk.getId() == null) {
            return;
        }
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            // ES 文档是 PG chunk 的关键词检索投影。chunk_id/version/content_hash 用于后续 reconcile，
            // 真正注入 prompt 的 content 仍以 PostgreSQL hydrate 结果为准。
            doc.put("chunk_id", chunk.getId());
            doc.put("source", chunk.getSource());
            doc.put("file_name", chunk.getFileName());
            doc.put("title", chunk.getTitle());
            doc.put("content", chunk.getContent());
            doc.put("content_hash", chunk.getContentHash());
            doc.put("version", chunk.getVersion());
            doc.put("status", chunk.getStatus());
            doc.put("updated_at", chunk.getUpdatedAt());

            HttpRequest request = requestBuilder(indexUri() + "/_doc/" + chunk.getId())
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(doc)))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                logger.warn("写入 ES RAG 索引失败: id={}, status={}, body={}", chunk.getId(), response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.warn("写入 ES RAG 索引失败: id={}, err={}", chunk.getId(), e.getMessage());
        }
    }

    public void deleteBySource(String source) {
        if (!enabled() || source == null || source.isBlank()) {
            return;
        }
        try {
            Map<String, Object> query = Map.of(
                    "query", Map.of(
                            "term", Map.of("source", source)
                    )
            );
            HttpRequest request = requestBuilder(indexUri() + "/_delete_by_query")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(query)))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                logger.warn("删除 ES RAG 旧索引失败: source={}, status={}", source, response.statusCode());
            }
        } catch (Exception e) {
            logger.warn("删除 ES RAG 旧索引失败: source={}, err={}", source, e.getMessage());
        }
    }

    public List<KeywordHit> searchKeyword(String query, int topK) {
        if (!enabled() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> body = Map.of(
                    "size", Math.max(1, topK),
                    "query", Map.of(
                            "bool", Map.of(
                                    "filter", List.of(Map.of("term", Map.of("status", "ACTIVE"))),
                                    "must", List.of(Map.of(
                                            // multi_match 查询 text 字段时，ES 默认使用 BM25 打分并返回 _score。
                                            // title/file_name 加权更高，用来提升标题、服务名、错误码等精确命中的排序。
                                            "multi_match", Map.of(
                                                    "query", query,
                                                    "fields", List.of("title^2", "content", "file_name^1.5")
                                            )
                                    ))
                            )
                    )
            );
            HttpRequest request = requestBuilder(indexUri() + "/_search")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                logger.warn("ES RAG 检索失败: status={}, body={}", response.statusCode(), response.body());
                return List.of();
            }
            return parseHits(response.body());
        } catch (Exception e) {
            logger.warn("ES RAG 检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<KeywordHit> parseHits(String body) throws Exception {
        List<KeywordHit> hits = new ArrayList<>();
        JsonNode root = objectMapper.readTree(body);
        JsonNode hitNodes = root.path("hits").path("hits");
        if (!hitNodes.isArray()) {
            return hits;
        }
        for (JsonNode hitNode : hitNodes) {
            String id = hitNode.path("_id").asText("");
            double score = hitNode.path("_score").asDouble(0D);
            if (!id.isBlank()) {
                hits.add(new KeywordHit(id, score));
            }
        }
        return hits;
    }

    private boolean enabled() {
        return ragProperties.getElasticsearch().isEnabled();
    }

    private String indexUri() {
        return trimTrailingSlash(ragProperties.getElasticsearch().getBaseUrl()) + "/" + ragProperties.getElasticsearch().getIndexName();
    }

    private HttpRequest.Builder requestBuilder(String uri) {
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofMillis(ragProperties.getElasticsearch().getTimeoutMillis()));
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:9200";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record KeywordHit(String id, double score) {
    }
}

package com.leap.agent.domain.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import com.leap.agent.common.config.RagProperties;
import com.leap.agent.infra.milvus.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 混合召回服务。
 *
 * <p>职责边界：
 * Milvus 负责语义向量召回，Elasticsearch 负责 BM25 关键词召回，Neo4j 负责 KG 图召回；
 * 三者都只提供候选 chunk id，最终内容必须回 PostgreSQL 事实源读取。</p>
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private RagDocumentChunkRepository chunkRepository;

    @Autowired
    private ElasticsearchRagIndexService elasticsearchRagIndexService;

    @Autowired
    private Neo4jRagKnowledgeGraphService knowledgeGraphService;

    @Autowired
    private RagProperties ragProperties;

    /**
     * 搜索相似文档
     * 
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        logger.info("开始搜索相似文档, 查询: {}, topK: {}", query, topK);

        Map<String, CandidateScore> candidates = new LinkedHashMap<>();

        // 先各取放大的候选池，再用 RRF 合并，避免不同召回源的原始分数尺度不可比。
        int fetchK = Math.max(topK, topK * Math.max(1, ragProperties.getKnowledgeGraph().getFetchMultiplier()));
        collectVectorCandidates(query, fetchK, candidates);
        collectKeywordCandidates(query, fetchK, candidates);
        collectGraphCandidates(query, fetchK, candidates);

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> orderedIds = candidates.entrySet().stream()
                .sorted((left, right) -> Double.compare(right.getValue().combinedScore(), left.getValue().combinedScore()))
                .map(Map.Entry::getKey)
                .toList();

        // 关键一致性点：索引命中不等于事实可用。这里回 PG 只取 ACTIVE chunk，
        // 能挡住 Milvus/ES/Neo4j 中尚未删除的陈旧候选，避免旧内容进入 prompt。
        List<RagDocumentChunk> chunks = chunkRepository.findActiveByIds(orderedIds);
        List<SearchResult> results = new ArrayList<>();
        for (RagDocumentChunk chunk : chunks) {
            CandidateScore score = candidates.get(chunk.getId());
            if (score == null) {
                continue;
            }
            SearchResult result = new SearchResult();
            result.setId(chunk.getId());
            result.setContent(chunk.getContent());
            result.setScore((float) score.combinedScore());
            result.setMetadata(String.valueOf(chunk.getMetadata()));
            result.setSource(chunk.getSource());
            result.setTitle(chunk.getTitle());
            result.setVectorScore(score.vectorScore());
            result.setKeywordScore(score.keywordScore());
            result.setGraphScore(score.graphScore());
            result.setRetrievalSources(new ArrayList<>(score.retrievalSources()));
            results.add(result);
            if (results.size() >= topK) {
                break;
            }
        }

        logger.info("搜索完成, 找到 {} 个相似文档", results.size());
        return results;
    }

    private void collectVectorCandidates(String query, int topK, Map<String, CandidateScore> candidates) {
        try {
            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(Math.max(1, topK))
                    .withMetricType(io.milvus.param.MetricType.L2)
                    // Milvus 只取 id/metadata，不取 content；content 字段只是兼容旧 collection schema 的占位。
                    .withOutFields(List.of("id", "metadata"))
                    .withParams("{\"nprobe\":10}")
                    .build();

            R<SearchResults> searchResponse = milvusClient.search(searchParam);
            if (searchResponse.getStatus() != 0) {
                logger.warn("向量搜索失败: {}", searchResponse.getMessage());
                return;
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                String id = (String) wrapper.getIDScore(0).get(i).get("id");
                if (id != null && !id.isBlank()) {
                    CandidateScore current = candidates.getOrDefault(id, CandidateScore.empty());
                    candidates.put(id, current.withVectorScore(rrfContribution(i, 1.0D)));
                }
            }
        } catch (Exception e) {
            logger.warn("Milvus 向量召回失败，尝试使用 ES/KG 召回: {}", e.getMessage());
        }
    }

    private void collectKeywordCandidates(String query, int topK, Map<String, CandidateScore> candidates) {
        List<ElasticsearchRagIndexService.KeywordHit> hits = elasticsearchRagIndexService.searchKeyword(query, Math.max(1, topK));
        for (int i = 0; i < hits.size(); i++) {
            ElasticsearchRagIndexService.KeywordHit hit = hits.get(i);
            if (hit.id() == null || hit.id().isBlank()) {
                continue;
            }
            CandidateScore current = candidates.getOrDefault(hit.id(), CandidateScore.empty());
            candidates.put(hit.id(), current.withKeywordScore(rrfContribution(i, 1.0D)));
        }
    }

    private void collectGraphCandidates(String query, int topK, Map<String, CandidateScore> candidates) {
        List<Neo4jRagKnowledgeGraphService.GraphHit> hits = knowledgeGraphService.searchGraph(query, Math.max(1, topK));
        double weight = ragProperties.getKnowledgeGraph().getWeight() > 0D
                ? ragProperties.getKnowledgeGraph().getWeight()
                : 1.0D;
        for (int i = 0; i < hits.size(); i++) {
            Neo4jRagKnowledgeGraphService.GraphHit hit = hits.get(i);
            if (hit.chunkId() == null || hit.chunkId().isBlank()) {
                continue;
            }
            CandidateScore current = candidates.getOrDefault(hit.chunkId(), CandidateScore.empty());
            candidates.put(hit.chunkId(), current.withGraphScore(rrfContribution(i, weight)));
        }
    }

    private double rrfContribution(int rank, double weight) {
        int rrfK = ragProperties.getKnowledgeGraph().getRrfK() > 0
                ? ragProperties.getKnowledgeGraph().getRrfK()
                : 60;
        return weight / (rrfK + rank + 1D);
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private double vectorScore;
        private double keywordScore;
        private double graphScore;
        private List<String> retrievalSources = new ArrayList<>();
        private String metadata;
        private String source;
        private String title;

    }

    private record CandidateScore(double vectorScore,
                                  double keywordScore,
                                  double graphScore,
                                  Set<String> retrievalSources) {
        static CandidateScore empty() {
            return new CandidateScore(0D, 0D, 0D, new LinkedHashSet<>());
        }

        CandidateScore withVectorScore(double score) {
            Set<String> sources = new LinkedHashSet<>(retrievalSources);
            sources.add("semantic");
            return new CandidateScore(vectorScore + score, keywordScore, graphScore, sources);
        }

        CandidateScore withKeywordScore(double score) {
            Set<String> sources = new LinkedHashSet<>(retrievalSources);
            sources.add("keyword");
            return new CandidateScore(vectorScore, keywordScore + score, graphScore, sources);
        }

        CandidateScore withGraphScore(double score) {
            Set<String> sources = new LinkedHashSet<>(retrievalSources);
            sources.add("graph");
            return new CandidateScore(vectorScore, keywordScore, graphScore + score, sources);
        }

        double combinedScore() {
            return vectorScore + keywordScore + graphScore;
        }
    }
}

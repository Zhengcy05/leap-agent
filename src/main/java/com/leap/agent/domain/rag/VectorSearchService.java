package com.leap.agent.domain.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import com.leap.agent.infra.milvus.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 混合召回服务。
 *
 * <p>职责边界：
 * Milvus 负责语义向量召回，Elasticsearch 负责 BM25 关键词召回；
 * 两者都只提供候选 chunk id，最终内容必须回 PostgreSQL 事实源读取。</p>
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

        // 先各取 topK*2 作为候选池，给两路召回留一点重叠和互补空间。
        // todo: AGI-saber 在这里还有 query rewrite、RRF 和 rerank；当前先用轻量加权合并跑通三层架构。
        collectVectorCandidates(query, topK * 2, candidates);
        collectKeywordCandidates(query, topK * 2, candidates);

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> orderedIds = candidates.entrySet().stream()
                .sorted((left, right) -> Double.compare(right.getValue().combinedScore(), left.getValue().combinedScore()))
                .map(Map.Entry::getKey)
                .toList();

        // 关键一致性点：索引命中不等于事实可用。这里回 PG 只取 ACTIVE chunk，
        // 能挡住 Milvus/ES 中尚未删除的陈旧候选，避免旧内容进入 prompt。
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
                float distance = wrapper.getIDScore(0).get(i).getScore();
                if (id != null && !id.isBlank()) {
                    CandidateScore current = candidates.getOrDefault(id, CandidateScore.empty());
                    // L2 距离越小越相似，这里压到 (0,1]，方便和 ES 的 keyword score 做轻量融合。
                    candidates.put(id, current.withVectorScore(1D / (1D + Math.max(0D, distance))));
                }
            }
        } catch (Exception e) {
            logger.warn("Milvus 向量召回失败，尝试仅使用 ES 关键词召回: {}", e.getMessage());
        }
    }

    private void collectKeywordCandidates(String query, int topK, Map<String, CandidateScore> candidates) {
        List<ElasticsearchRagIndexService.KeywordHit> hits = elasticsearchRagIndexService.searchKeyword(query, Math.max(1, topK));
        // ES 返回的是 BM25 _score，绝对值会随 query 和语料分布变化；先按本次最大分归一化。
        // todo: 更完整的 AGI-saber 路线是 RRF(rank based) + reranker，避免不同召回器分数尺度不可比。
        double maxScore = hits.stream()
                .mapToDouble(ElasticsearchRagIndexService.KeywordHit::score)
                .max()
                .orElse(0D);
        for (ElasticsearchRagIndexService.KeywordHit hit : hits) {
            if (hit.id() == null || hit.id().isBlank()) {
                continue;
            }
            double normalizedScore = maxScore > 0D ? hit.score() / maxScore : hit.score();
            CandidateScore current = candidates.getOrDefault(hit.id(), CandidateScore.empty());
            candidates.put(hit.id(), current.withKeywordScore(normalizedScore));
        }
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
        private String metadata;
        private String source;
        private String title;

    }

    private record CandidateScore(double vectorScore, double keywordScore) {
        static CandidateScore empty() {
            return new CandidateScore(0D, 0D);
        }

        CandidateScore withVectorScore(double score) {
            return new CandidateScore(Math.max(vectorScore, score), keywordScore);
        }

        CandidateScore withKeywordScore(double score) {
            return new CandidateScore(vectorScore, Math.max(keywordScore, score));
        }

        double combinedScore() {
            // 当前是能用的简化融合：语义召回更重，关键词召回补精确术语、服务名、错误码。
            // 后续接 RRF/rerank 时可以只替换这里和候选排序，不影响 PG hydrate 事实源边界。
            return vectorScore * 0.70D + keywordScore * 0.30D;
        }
    }
}

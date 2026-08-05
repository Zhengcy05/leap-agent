package com.leap.agent.domain.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RAG RRF 之后的精排服务。
 */
@Service
public class RagRerankerService {

    private static final Logger logger = LoggerFactory.getLogger(RagRerankerService.class);
    private static final String PROVIDER_DASHSCOPE_RERANK = "dashscope-rerank";
    private static final String PROVIDER_LLM = "llm";
    private static final String PROVIDER_NONE = "none";
    private static final String DEFAULT_RERANK_MODEL = "qwen3-vl-rerank";

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${spring.ai.dashscope.api-key:${dashscope.api.key:}}")
    private String dashScopeApiKey;

    /**
     * 构造 rerank 服务依赖。
     */
    public RagRerankerService(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(resolveTimeoutMillis(ragProperties)))
                .build();
    }

    /**
     * 对 RRF 候选做精排，失败时返回原始顺序。
     */
    public RerankResult rerank(String query, List<VectorSearchService.SearchResult> candidates, int topK) {
        List<VectorSearchService.SearchResult> fallback = truncate(candidates, topK);
        if (!shouldRerank(query, candidates, topK)) {
            return new RerankResult(fallback, false);
        }
        String provider = resolveProvider();
        if (PROVIDER_NONE.equals(provider)) {
            return new RerankResult(fallback, false);
        }
        if (PROVIDER_LLM.equals(provider)) {
            return rerankWithLlmOrFallback(query, candidates, topK, fallback);
        }
        RerankResult dedicatedResult = rerankWithDedicatedModel(query, candidates, topK);
        if (dedicatedResult.applied()) {
            return dedicatedResult;
        }
        if (ragProperties.getRetrieval().isRerankLlmFallbackEnabled()) {
            return rerankWithLlmOrFallback(query, candidates, topK, fallback);
        }
        return fallbackResult("DashScope 专用 rerank 失败，回退 RRF 顺序", fallback);
    }

    /**
     * 判断当前查询和候选是否需要 rerank。
     */
    private boolean shouldRerank(String query, List<VectorSearchService.SearchResult> candidates, int topK) {
        return ragProperties.getRetrieval().isRerankEnabled()
                && hasValidApiKey()
                && query != null
                && !query.isBlank()
                && candidates != null
                && candidates.size() > Math.max(1, topK)
                && candidates.size() >= resolveMinRerankCandidates();
    }

    /**
     * 使用 DashScope 专用 rerank 模型排序。
     */
    private RerankResult rerankWithDedicatedModel(String query,
                                                  List<VectorSearchService.SearchResult> candidates,
                                                  int topK) {
        try {
            Map<Integer, Double> scores = callDashScopeRerank(query, candidates, topK);
            if (scores.isEmpty()) {
                return new RerankResult(truncate(candidates, topK), false);
            }
            return new RerankResult(sortByRerankScore(candidates, scores, topK, PROVIDER_DASHSCOPE_RERANK), true);
        } catch (Exception e) {
            logger.debug("DashScope 专用 rerank 失败: {}", e.getMessage());
            return new RerankResult(truncate(candidates, topK), false);
        }
    }

    /**
     * 使用普通 LLM listwise 精排并在失败时回退。
     */
    private RerankResult rerankWithLlmOrFallback(String query,
                                                 List<VectorSearchService.SearchResult> candidates,
                                                 int topK,
                                                 List<VectorSearchService.SearchResult> fallback) {
        try {
            String raw = callLlmRerankModel(query, candidates);
            Map<Integer, Double> scores = parseLlmScores(raw, candidates.size());
            if (scores.isEmpty()) {
                return fallbackResult("LLM rerank 解析结果为空，回退 RRF 顺序", fallback);
            }
            return new RerankResult(sortByRerankScore(candidates, scores, topK, PROVIDER_LLM), true);
        } catch (Exception e) {
            logger.debug("LLM rerank 失败，回退 RRF 顺序: {}", e.getMessage());
            return new RerankResult(fallback, false);
        }
    }

    /**
     * 调用 DashScope rerank HTTP API 获取候选相关性分数。
     */
    private Map<Integer, Double> callDashScopeRerank(String query,
                                                     List<VectorSearchService.SearchResult> candidates,
                                                     int topK) throws Exception {
        List<Map<String, String>> documents = new ArrayList<>();
        int previewLen = Math.max(50, ragProperties.getRetrieval().getRerankPreviewLen());
        for (VectorSearchService.SearchResult candidate : candidates) {
            documents.add(Map.of("text", preview(candidate.getContent(), previewLen)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolveDedicatedRerankModel());
        body.put("input", Map.of(
                "query", Map.of("text", query),
                "documents", documents
        ));
        body.put("parameters", Map.of(
                "return_documents", false,
                "top_n", Math.max(1, topK)
        ));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(ragProperties.getRetrieval().getRerankBaseUrl())
                        + "/services/rerank/text-rerank/text-rerank"))
                .timeout(Duration.ofMillis(resolveTimeoutMillis(ragProperties)))
                .header("Authorization", "Bearer " + dashScopeApiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            logger.debug("DashScope 专用 rerank HTTP 失败: status={}, body={}", response.statusCode(), response.body());
            return Map.of();
        }
        return parseDedicatedScores(response.body(), candidates.size());
    }

    /**
     * 调用 DashScope ChatModel 获取候选相关性分数。
     */
    private String callLlmRerankModel(String query, List<VectorSearchService.SearchResult> candidates) {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
        DashScopeChatModel chatModel = DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(resolveLlmRerankModel())
                        .withTemperature(0.0)
                        .withMaxToken(900)
                        .withTopP(0.8)
                        .build())
                .build();
        ChatResponse response = chatModel.call(new Prompt(buildPrompt(query, candidates)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 构造 listwise rerank 提示词。
     */
    private String buildPrompt(String query, List<VectorSearchService.SearchResult> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                你是检索系统的精排器。给定用户问题和若干候选段落（每条带编号 idx），
                判断每条段落对回答该问题的相关性和信息密度，给 0 到 10 的整数分。

                打分准则：
                - 10：直接回答了问题
                - 7-9：包含明确相关事实
                - 4-6：弱相关或部分相关
                - 1-3：仅出现共现关键词，不能用来回答
                - 0：无关或噪声

                输出严格 JSON，不要解释，不要 markdown 代码块：
                {"scores":[{"idx":0,"score":9},{"idx":1,"score":3}]}

                约束：
                - 不依赖你自己的知识，只看给出的段落
                - idx 必须对应候选段落编号
                - score 必须是 0 到 10 的数字

                用户问题：
                """);
        builder.append(query).append("\n\n候选段落：\n");
        int previewLen = Math.max(50, ragProperties.getRetrieval().getRerankPreviewLen());
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchService.SearchResult result = candidates.get(i);
            builder.append("[").append(i).append("]\n");
            builder.append("title: ").append(safe(result.getTitle())).append("\n");
            builder.append("source: ").append(safe(result.getSource())).append("\n");
            builder.append("retrieval_sources: ").append(result.getRetrievalSources()).append("\n");
            builder.append("content: ").append(preview(result.getContent(), previewLen)).append("\n\n");
        }
        return builder.toString();
    }

    /**
     * 解析专用 rerank 模型返回的 JSON 分数。
     */
    private Map<Integer, Double> parseDedicatedScores(String raw, int candidateSize) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<Integer, Double> scores = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(raw);
            for (JsonNode resultNode : root.path("output").path("results")) {
                int idx = resultNode.path("index").asInt(-1);
                if (idx < 0 || idx >= candidateSize) {
                    continue;
                }
                double score = resultNode.path("relevance_score").asDouble(-1D);
                if (score < 0D) {
                    continue;
                }
                scores.put(idx, Math.max(0D, Math.min(1D, score)) * 10D);
            }
        } catch (Exception e) {
            logger.debug("DashScope 专用 rerank JSON 解析失败: {}", e.getMessage());
            return Map.of();
        }
        return scores;
    }

    /**
     * 解析 LLM reranker 返回的 JSON 分数。
     */
    private Map<Integer, Double> parseLlmScores(String raw, int candidateSize) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String cleaned = raw.trim().replace("```json", "").replace("```", "").trim();
        Map<Integer, Double> scores = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            for (JsonNode scoreNode : root.path("scores")) {
                int idx = scoreNode.path("idx").asInt(-1);
                if (idx < 0 || idx >= candidateSize) {
                    continue;
                }
                double score = scoreNode.path("score").asDouble(-1D);
                if (score < 0D) {
                    continue;
                }
                scores.put(idx, Math.max(0D, Math.min(10D, score)));
            }
        } catch (Exception e) {
            logger.debug("RAG rerank JSON 解析失败: {}", e.getMessage());
            return Map.of();
        }
        return scores;
    }

    /**
     * 用 rerank 分数排序候选并截断。
     */
    private List<VectorSearchService.SearchResult> sortByRerankScore(List<VectorSearchService.SearchResult> candidates,
                                                                     Map<Integer, Double> scores,
                                                                     int topK,
                                                                     String provider) {
        List<ScoredResult> pool = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchService.SearchResult result = candidates.get(i);
            double rerankScore = scores.getOrDefault(i, -1D);
            pool.add(new ScoredResult(i, rerankScore, result.getRrfScore(), result));
        }
        pool.sort(Comparator
                .comparingDouble(ScoredResult::rerankScore).reversed()
                .thenComparing(Comparator.comparingDouble(ScoredResult::rrfScore).reversed())
                .thenComparingInt(ScoredResult::index));

        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        int limit = topK > 0 ? Math.min(topK, pool.size()) : pool.size();
        for (int i = 0; i < limit; i++) {
            ScoredResult scored = pool.get(i);
            VectorSearchService.SearchResult result = scored.result();
            if (scored.rerankScore() >= 0D) {
                result.setRerankScore(scored.rerankScore());
                result.setScore((float) (scored.rerankScore() / 10D));
            } else {
                result.setScore((float) result.getRrfScore());
            }
            result.setReranked(true);
            result.setRerankProvider(provider);
            results.add(result);
        }
        return results;
    }

    /**
     * 截断候选列表。
     */
    private List<VectorSearchService.SearchResult> truncate(List<VectorSearchService.SearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int limit = topK > 0 ? Math.min(topK, candidates.size()) : candidates.size();
        return new ArrayList<>(candidates.subList(0, limit));
    }

    /**
     * 解析 rerank 提供方。
     */
    private String resolveProvider() {
        String provider = ragProperties.getRetrieval().getRerankProvider();
        if (provider == null || provider.isBlank()) {
            return PROVIDER_DASHSCOPE_RERANK;
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        if (PROVIDER_LLM.equals(normalized) || PROVIDER_NONE.equals(normalized)) {
            return normalized;
        }
        return PROVIDER_DASHSCOPE_RERANK;
    }

    /**
     * 解析专用 rerank 使用的模型名。
     */
    private String resolveDedicatedRerankModel() {
        String configured = ragProperties.getRetrieval().getRerankModel();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return DEFAULT_RERANK_MODEL;
    }

    /**
     * 解析 LLM fallback 使用的模型名。
     */
    private String resolveLlmRerankModel() {
        String configured = ragProperties.getRetrieval().getRerankModel();
        if (configured != null && !configured.isBlank() && !DEFAULT_RERANK_MODEL.equals(configured.trim())) {
            return configured.trim();
        }
        String ragModel = ragProperties.getModel();
        if (ragModel != null && !ragModel.isBlank()) {
            return ragModel.trim();
        }
        return DashScopeChatModel.DEFAULT_MODEL_NAME;
    }

    /**
     * 构造带日志的 RRF 回退结果。
     */
    private RerankResult fallbackResult(String message, List<VectorSearchService.SearchResult> fallback) {
        logger.debug(message);
        return new RerankResult(fallback, false);
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
     * 截取候选正文预览。
     */
    private String preview(String content, int maxCodePoints) {
        String value = safe(content);
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, endIndex);
    }

    /**
     * 规避空字符串进入 prompt。
     */
    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 去掉 URL 末尾斜杠。
     */
    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://dashscope.aliyuncs.com/api/v1";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 解析 rerank 请求超时时间。
     */
    private static int resolveTimeoutMillis(RagProperties ragProperties) {
        int timeoutMillis = ragProperties.getRetrieval().getRerankTimeoutMillis();
        return timeoutMillis > 0 ? timeoutMillis : 8000;
    }

    /**
     * 解析触发 rerank 的最小候选数量。
     */
    private int resolveMinRerankCandidates() {
        int minCandidates = ragProperties.getRetrieval().getMinRerankCandidates();
        return minCandidates > 0 ? minCandidates : 20;
    }

    public record RerankResult(List<VectorSearchService.SearchResult> results, boolean applied) {
    }

    private record ScoredResult(int index,
                                double rerankScore,
                                double rrfScore,
                                VectorSearchService.SearchResult result) {
    }
}

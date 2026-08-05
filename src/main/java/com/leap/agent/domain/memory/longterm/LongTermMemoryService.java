package com.leap.agent.domain.memory.longterm;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.agent.common.config.MemoryProperties;
import com.leap.agent.domain.rag.VectorEmbeddingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆服务。
 *
 * <p>长期记忆不重复承接回复语言、地域、时间范围等偏好槽位；
 * 这些由 PreferenceMemoryService 负责。这里只在回复结束后异步调用 LLM 抽取，
 * 再通过置信度、同类去重、合并、衰减和过期淘汰来治理真正可复用的事实、排障案例和工具经验。</p>
 */
@Service
public class LongTermMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final String OWNER_ID = "leap-agent";
    private static final int MAX_CONTENT_LENGTH = 180;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[a-z0-9][a-z0-9\\-]{1,}");

    private final LongTermMemoryRepository repository;
    private final MemoryProperties memoryProperties;
    private final VectorEmbeddingService vectorEmbeddingService;
    private final ObjectMapper objectMapper;
    private final Neo4jMemoryGraphClient memoryGraphClient;
    private final LongTermMemoryConsolidationService consolidationService;
    private final Map<String, LongTermMemoryEntry> memories = new ConcurrentHashMap<>();
    private int newMemoryCountSinceConsolidation;
    private final ExecutorService extractionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "long-term-memory-writer");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    public LongTermMemoryService(LongTermMemoryRepository repository,
                                 MemoryProperties memoryProperties,
                                 VectorEmbeddingService vectorEmbeddingService,
                                 ObjectMapper objectMapper,
                                 Neo4jMemoryGraphClient memoryGraphClient,
                                 LongTermMemoryConsolidationService consolidationService) {
        this.repository = repository;
        this.memoryProperties = memoryProperties;
        this.vectorEmbeddingService = vectorEmbeddingService;
        this.objectMapper = objectMapper;
        this.memoryGraphClient = memoryGraphClient;
        this.consolidationService = consolidationService;
    }

    @PostConstruct
    public void loadPersistedMemories() {
        List<LongTermMemoryEntry> persisted = repository.loadAll(OWNER_ID);
        for (LongTermMemoryEntry entry : persisted) {
            if (entry != null && entry.getId() != null && !entry.getId().isBlank()) {
                normalizeLoadedEntry(entry);
                memories.put(entry.getId(), entry);
            }
        }
        syncGraphProjection(persisted);
        logger.info("已加载长期记忆 {} 条", memories.size());
    }

    /**
     * 构建可直接注入 system prompt 的长期记忆片段。
     */
    public String buildPromptSection(String query) {
        List<LongTermMemoryEntry> recalled = recall(query);
        if (recalled.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder("【长期记忆 / 相关事实】\n");
        for (LongTermMemoryEntry entry : recalled) {
            String categoryName = LongTermMemoryCategory.fromValue(entry.getCategory())
                    .map(LongTermMemoryCategory::displayName)
                    .orElse(LongTermMemoryCategory.GENERAL.displayName());
            builder.append("- ")
                    .append(categoryName)
                    .append(": ")
                    .append(entry.getContent())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    /**
     * 回复结束后异步抽取长期记忆，失败只记日志，不影响本轮对话。
     */
    public void recordTurnAsync(String sessionId, String userQuestion, String assistantAnswer) {
        if (!memoryProperties.getLongTerm().isAsyncExtractionEnabled()) {
            return;
        }
        if ((userQuestion == null || userQuestion.isBlank()) && (assistantAnswer == null || assistantAnswer.isBlank())) {
            return;
        }

        extractionExecutor.execute(() -> {
            try {
                writeTurn(sessionId, userQuestion, assistantAnswer);
            } catch (Exception e) {
                logger.warn("长期记忆异步写入失败: {}", e.getMessage());
            }
        });
    }

    public List<LongTermMemoryEntry> snapshot() {
        return memories.values().stream()
                .sorted(Comparator
                        .comparingDouble(LongTermMemoryEntry::getImportance).reversed()
                        .thenComparing(LongTermMemoryEntry::getLastAccessed, Comparator.reverseOrder())
                        .thenComparing(LongTermMemoryEntry::getCreatedAt, Comparator.reverseOrder()))
                .toList();
    }

    /**
     * 读取长期记忆图统计，用于调试接口展示。
     */
    public Map<String, Neo4jMemoryGraphClient.GraphStats> graphStatsSnapshot() {
        List<String> memoryIds = snapshot().stream()
                .map(LongTermMemoryEntry::getId)
                .toList();
        return memoryGraphClient.readStats(OWNER_ID, memoryIds);
    }

    @PreDestroy
    public void shutdownExecutor() {
        extractionExecutor.shutdown();
    }

    /**
     * 按当前问题召回长期记忆，再用 Neo4j 做关联扩展。
     */
    private synchronized List<LongTermMemoryEntry> recall(String query) {
        if (query == null || query.isBlank() || memories.isEmpty()) {
            return List.of();
        }

        List<Float> queryEmbedding = buildEmbedding(query);
        long now = System.currentTimeMillis();
        List<LongTermMemoryEntry> scored = new ArrayList<>();
        for (LongTermMemoryEntry entry : memories.values()) {
            if (!entry.isActive() || entry.getContent() == null || entry.getContent().isBlank()) {
                continue;
            }
            // 召回不是纯语义相似度：身份/偏好/约束这类记忆即使措辞不同，也需要靠重要性和类别偏置浮上来。
            double similarity = semanticSimilarity(query, queryEmbedding, entry);
            double score = similarity * 0.72D
                    + entry.getImportance() * 0.20D
                    + recencyBoost(entry.getCreatedAt(), now) * 0.04D
                    + categoryBoost(query, entry);
            if (score >= memoryProperties.getLongTerm().getMinRecallScore()) {
                entry.setScore(score);
                entry.setLastAccessed(now);
                scored.add(entry);
            }
        }

        List<LongTermMemoryEntry> seedItems = scored.stream()
                .sorted(Comparator.comparingDouble(LongTermMemoryEntry::getScore).reversed())
                .limit(Math.max(1, memoryProperties.getLongTerm().getPromptTopK()))
                .toList();
        return expandRecallWithGraph(seedItems, now);
    }

    private void writeTurn(String sessionId, String userQuestion, String assistantAnswer) {
        if (!shouldRunLlmExtraction(userQuestion, assistantAnswer)) {
            return;
        }
        persistCandidates(extractLlmCandidates(sessionId, userQuestion, assistantAnswer));
    }

    private boolean shouldRunLlmExtraction(String userQuestion, String assistantAnswer) {
        if (!memoryProperties.getLongTerm().isAsyncLlmEnabled() || !hasValidApiKey()) {
            return false;
        }

        String joined = compact(safe(userQuestion) + "\n" + safe(assistantAnswer));
        return !joined.isBlank();
    }

    private List<LongTermMemoryCandidate> extractLlmCandidates(String sessionId, String userQuestion, String assistantAnswer) {
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
                请从下面这一轮对话中提取对 oncallAgent 有长期复用价值的记忆。
                必须只输出 JSON 对象，不要解释，不要 markdown 代码块。

                输出结构：
                {
                  "items": [
                    {
                      "category": "troubleshooting_case",
                      "content": "order-service CPU 高时，先按实例维度查看 Prometheus 再关联最近发布",
                      "tags": ["incident", "order-service"],
                      "confidence": 0.9
                    }
                  ]
                }

                category 只允许：
                - policy: 用户给出的长期硬性约束
                - fact: 跨会话仍可能复用的系统事实、业务事实、服务依赖、告警背景
                - troubleshooting_case: 已确认的问题现象、排查路径、根因或解决思路
                - tool_lesson: 工具调用、查询语句、参数组合或排障流程中的稳定经验
                - general: 以上都不适合但确实值得记住的信息

                要求：
                1. 优先提取可复用的排障经验、工具经验、系统事实、用户确认的结论
                2. 不要把一次性任务、临时上下文、助手猜测写成长期记忆
                3. content 使用简短中文句子，不超过 80 字
                4. 不要输出用户画像、回复语言、回复风格、默认地域、默认时间范围等偏好记忆；它们由偏好记忆模块处理
                5. 如果没有值得记忆的内容，输出 {"items":[]}

                用户消息：
                """ + safe(userQuestion) + """

                助手回复：
                """ + safe(assistantAnswer);

        try {
            ChatResponse response = chatModel.call(new Prompt(prompt));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return List.of();
            }
            String raw = response.getResult().getOutput().getText();
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return parseLlmCandidates(sessionId, raw);
        } catch (Exception e) {
            logger.debug("长期记忆 LLM 抽取失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<LongTermMemoryCandidate> parseLlmCandidates(String sessionId, String raw) {
        String cleaned = raw.trim().replace("```json", "").replace("```", "").trim();
        List<LongTermMemoryCandidate> candidates = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode itemsNode = root.path("items");
            if (!itemsNode.isArray()) {
                return candidates;
            }
            for (JsonNode itemNode : itemsNode) {
                String category = normalizeCategory(itemNode.path("category").asText(LongTermMemoryCategory.GENERAL.value()));
                String content = normalizeContent(itemNode.path("content").asText(""));
                double confidence = itemNode.path("confidence").asDouble(0D);
                if (content.isBlank() || confidence < memoryProperties.getLongTerm().getMinConfidence()) {
                    continue;
                }
                candidates.add(new LongTermMemoryCandidate(
                        sessionId,
                        category,
                        content,
                        parseTags(itemNode.path("tags")),
                        importanceFor(category),
                        confidence,
                        LongTermMemorySource.LLM));
            }
        } catch (Exception e) {
            logger.debug("解析长期记忆 LLM 结果失败: {}", e.getMessage());
        }
        return candidates;
    }

    private synchronized void persistCandidates(List<LongTermMemoryCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        Map<String, LongTermMemoryCandidate> uniqueCandidates = new LinkedHashMap<>();
        for (LongTermMemoryCandidate candidate : candidates) {
            if (candidate.content().isBlank()
                    || candidate.confidence() < memoryProperties.getLongTerm().getMinConfidence()) {
                continue;
            }
            // 本轮内先做精确归一去重；跨轮的近重复再交给 upsertCandidate 里的文本/向量判断。
            uniqueCandidates.putIfAbsent(candidate.category() + "|" + normalizeForCompare(candidate.content()), candidate);
        }

        boolean changed = false;
        for (LongTermMemoryCandidate candidate : uniqueCandidates.values()) {
            String previousMemoryId = findLatestActiveMemoryId();
            List<Float> embedding = buildEmbedding(candidate.content());
            LongTermMemoryConsolidationService.UpsertResult result = consolidationService.upsertCandidate(
                    OWNER_ID,
                    memories,
                    candidate,
                    embedding,
                    previousMemoryId);
            changed |= result.changed();
            if (result.addedNewMemory()) {
                newMemoryCountSinceConsolidation++;
            }
        }
        LongTermMemoryConsolidationService.ConsolidationResult consolidationResult =
                consolidationService.consolidateIfNeeded(OWNER_ID, memories, newMemoryCountSinceConsolidation);
        newMemoryCountSinceConsolidation = consolidationResult.newMemoryCountSinceConsolidation();
        changed |= consolidationResult.changed();
        if (changed) {
            repository.saveAll(OWNER_ID, snapshotForPersistence());
        }
    }

    private void normalizeLoadedEntry(LongTermMemoryEntry entry) {
        entry.setCategory(entry.getCategory());
        entry.setTags(entry.getTags());
        entry.setEmbedding(entry.getEmbedding());
        entry.setSource(entry.getSource() == null ? LongTermMemorySource.BOOTSTRAP : entry.getSource());
        entry.setStatus(entry.getStatus());
        if (entry.getCreatedAt() <= 0) {
            entry.setCreatedAt(System.currentTimeMillis());
        }
        if (entry.getLastAccessed() <= 0) {
            entry.setLastAccessed(entry.getCreatedAt());
        }
        if (entry.getVersion() <= 0) {
            entry.setVersion(1L);
        }
    }

    private List<LongTermMemoryEntry> snapshotForPersistence() {
        return memories.values().stream()
                .sorted(Comparator.comparing(LongTermMemoryEntry::getCreatedAt))
                .toList();
    }

    /**
     * 根据已加载的权威记忆快照重建 Neo4j 图节点和时序边。
     */
    private void syncGraphProjection(List<LongTermMemoryEntry> persisted) {
        if (!memoryGraphClient.isAvailable() || persisted == null || persisted.isEmpty()) {
            return;
        }

        List<LongTermMemoryEntry> activeEntries = persisted.stream()
                .filter(entry -> entry != null && entry.isActive() && entry.getId() != null && !entry.getId().isBlank())
                .sorted(Comparator.comparingLong(LongTermMemoryEntry::getCreatedAt))
                .toList();
        String previousMemoryId = null;
        for (LongTermMemoryEntry entry : activeEntries) {
            memoryGraphClient.upsertMemory(OWNER_ID, entry);
            if (previousMemoryId != null) {
                memoryGraphClient.addFollows(OWNER_ID, previousMemoryId, entry.getId());
            }
            previousMemoryId = entry.getId();
        }
        logger.info("已同步长期记忆图投影 {} 个节点", activeEntries.size());
    }

    /**
     * 基于 seed 记忆从 Neo4j 扩展邻居，并回内存快照过滤 active 条目。
     */
    private List<LongTermMemoryEntry> expandRecallWithGraph(List<LongTermMemoryEntry> seedItems, long now) {
        if (!memoryGraphClient.isAvailable() || seedItems == null || seedItems.isEmpty()) {
            return seedItems == null ? List.of() : seedItems;
        }

        int topK = Math.max(1, memoryProperties.getLongTerm().getPromptTopK());
        int hops = memoryProperties.getLongTerm().getGraph().getNeighborHops();
        List<String> seedIds = seedItems.stream()
                .map(LongTermMemoryEntry::getId)
                .toList();
        List<String> expandedIds = memoryGraphClient.expandMemoryIds(OWNER_ID, seedIds, hops);
        if (expandedIds.isEmpty()) {
            return seedItems;
        }

        Set<String> seenIds = new LinkedHashSet<>(seedIds);
        List<LongTermMemoryEntry> combined = new ArrayList<>(seedItems);
        for (String expandedId : expandedIds) {
            if (!seenIds.add(expandedId)) {
                continue;
            }
            LongTermMemoryEntry entry = memories.get(expandedId);
            if (entry == null || !entry.isActive() || entry.getContent() == null || entry.getContent().isBlank()) {
                continue;
            }
            entry.setScore(memoryProperties.getLongTerm().getGraph().getGraphExpandedScore());
            entry.setLastAccessed(now);
            combined.add(entry);
        }

        return combined.stream()
                .sorted(Comparator.comparingDouble(LongTermMemoryEntry::getScore).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * 找出当前最新的 active 记忆，用于建立 FOLLOWS 边。
     */
    private String findLatestActiveMemoryId() {
        return memories.values().stream()
                .filter(entry -> entry != null && entry.isActive() && entry.getId() != null && !entry.getId().isBlank())
                .max(Comparator.comparingLong(LongTermMemoryEntry::getCreatedAt))
                .map(LongTermMemoryEntry::getId)
                .orElse(null);
    }

    private double semanticSimilarity(String query, List<Float> queryEmbedding, LongTermMemoryEntry entry) {
        if (!queryEmbedding.isEmpty()
                && entry.getEmbedding() != null
                && entry.getEmbedding().size() == queryEmbedding.size()) {
            return cosine(queryEmbedding, entry.getEmbedding());
        }
        // 与 成品项目 一样，embedding 不可用或维度不匹配时降级到 TF cosine；TF 词表由内容临时重建，无需单独持久化。
        return tfSimilarity(query, entry.getContent());
    }

    private List<Float> buildEmbedding(String content) {
        try {
            if (content == null || content.isBlank()) {
                return List.of();
            }
            return vectorEmbeddingService.generateEmbedding(content);
        } catch (Exception e) {
            logger.debug("长期记忆向量生成失败，使用文本相似度降级: {}", e.getMessage());
            return List.of();
        }
    }

    private double cosine(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.size() != right.size() || left.isEmpty()) {
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int i = 0; i < left.size(); i++) {
            double leftValue = left.get(i);
            double rightValue = right.get(i);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0D || rightNorm == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private double tfSimilarity(String query, String content) {
        Map<String, Integer> queryVector = termFrequency(query);
        Map<String, Integer> contentVector = termFrequency(content);
        if (queryVector.isEmpty() || contentVector.isEmpty()) {
            return 0D;
        }

        double dot = 0D;
        double queryNorm = 0D;
        double contentNorm = 0D;
        for (int value : queryVector.values()) {
            queryNorm += value * value;
        }
        for (int value : contentVector.values()) {
            contentNorm += value * value;
        }
        for (Map.Entry<String, Integer> entry : queryVector.entrySet()) {
            Integer contentValue = contentVector.get(entry.getKey());
            if (contentValue != null) {
                dot += entry.getValue() * contentValue;
            }
        }
        if (queryNorm == 0D || contentNorm == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(queryNorm) * Math.sqrt(contentNorm));
    }

    private Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> tokens = new HashMap<>();
        Matcher matcher = TOKEN_PATTERN.matcher(safe(text).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.merge(matcher.group(), 1, Integer::sum);
        }
        return tokens;
    }

    private double recencyBoost(long createdAt, long now) {
        if (createdAt <= 0 || now <= createdAt) {
            return 1D;
        }
        double ageDays = (now - createdAt) / 86_400_000D;
        return 1D / (1D + ageDays / 30D);
    }

    private double categoryBoost(String query, LongTermMemoryEntry entry) {
        String normalizedQuery = safe(query);
        String category = entry.getCategory();
        if (LongTermMemoryCategory.IDENTITY.value().equals(category)
                && normalizedQuery.contains("我")
                && (normalizedQuery.contains("名字") || normalizedQuery.contains("叫") || normalizedQuery.contains("身份"))) {
            return 0.22D;
        }
        if ((LongTermMemoryCategory.PREFERENCE.value().equals(category) || LongTermMemoryCategory.POLICY.value().equals(category))
                && (normalizedQuery.contains("偏好") || normalizedQuery.contains("习惯")
                || normalizedQuery.contains("默认") || normalizedQuery.contains("记得"))) {
            return 0.18D;
        }
        return 0D;
    }

    private double importanceFor(String category) {
        return switch (normalizeCategory(category)) {
            case "identity" -> 0.9D;
            case "policy" -> 0.84D;
            case "preference" -> 0.78D;
            case "troubleshooting_case" -> 0.72D;
            case "tool_lesson" -> 0.68D;
            case "fact" -> 0.62D;
            default -> 0.50D;
        };
    }

    private List<String> parseTags(JsonNode tagsNode) {
        List<String> tags = new ArrayList<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tagNode : tagsNode) {
                String tag = tagNode.asText("");
                if (!tag.isBlank()) {
                    tags.add(tag.trim());
                }
            }
        }
        return tags;
    }

    private String normalizeCategory(String category) {
        return LongTermMemoryCategory.fromValue(category)
                .map(LongTermMemoryCategory::value)
                .orElse(LongTermMemoryCategory.GENERAL.value());
    }

    private String normalizeContent(String content) {
        String normalized = compact(content);
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTENT_LENGTH) + "...";
        }
        return normalized;
    }

    private String normalizeForCompare(String content) {
        return safe(content)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s，。！？；：、“”‘’（）()【】\\[\\]]+", "");
    }

    private String compact(String text) {
        return safe(text).replaceAll("\\s+", " ").trim();
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private boolean hasValidApiKey() {
        return dashScopeApiKey != null
                && !dashScopeApiKey.isBlank()
                && !dashScopeApiKey.contains("your-api-key");
    }

}

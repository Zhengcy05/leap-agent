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
import java.util.UUID;
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
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.memoryProperties = memoryProperties;
        this.vectorEmbeddingService = vectorEmbeddingService;
        this.objectMapper = objectMapper;
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

    @PreDestroy
    public void shutdownExecutor() {
        extractionExecutor.shutdown();
    }

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

        return scored.stream()
                .sorted(Comparator.comparingDouble(LongTermMemoryEntry::getScore).reversed())
                .limit(Math.max(1, memoryProperties.getLongTerm().getPromptTopK()))
                .toList();
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

    private List<MemoryCandidate> extractLlmCandidates(String sessionId, String userQuestion, String assistantAnswer) {
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

    private List<MemoryCandidate> parseLlmCandidates(String sessionId, String raw) {
        String cleaned = raw.trim().replace("```json", "").replace("```", "").trim();
        List<MemoryCandidate> candidates = new ArrayList<>();
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
                candidates.add(new MemoryCandidate(
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

    private synchronized void persistCandidates(List<MemoryCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        Map<String, MemoryCandidate> uniqueCandidates = new LinkedHashMap<>();
        for (MemoryCandidate candidate : candidates) {
            if (candidate.content().isBlank()
                    || candidate.confidence() < memoryProperties.getLongTerm().getMinConfidence()) {
                continue;
            }
            // 本轮内先做精确归一去重；跨轮的近重复再交给 upsertCandidate 里的文本/向量判断。
            uniqueCandidates.putIfAbsent(candidate.category() + "|" + normalizeForCompare(candidate.content()), candidate);
        }

        boolean changed = false;
        for (MemoryCandidate candidate : uniqueCandidates.values()) {
            changed |= upsertCandidate(candidate);
        }
        changed |= consolidateIfNeeded();
        if (changed) {
            repository.saveAll(OWNER_ID, snapshotForPersistence());
        }
    }

    private boolean upsertCandidate(MemoryCandidate candidate) {
        List<Float> embedding = buildEmbedding(candidate.content());
        LongTermMemoryEntry duplicate = findDuplicate(candidate, embedding);
        long now = System.currentTimeMillis();
        if (duplicate != null) {
            mergeIntoExisting(duplicate, candidate, embedding, now);
            return true;
        }

        LongTermMemoryEntry entry = new LongTermMemoryEntry(
                UUID.randomUUID().toString(),
                candidate.sessionId(),
                candidate.category(),
                candidate.content(),
                candidate.tags(),
                candidate.importance(),
                candidate.confidence(),
                candidate.source(),
                embedding,
                now,
                now,
                1L
        );
        memories.put(entry.getId(), entry);
        newMemoryCountSinceConsolidation++;
        logger.info("新增长期记忆: [{}] {}", entry.getCategory(), entry.getContent());
        return true;
    }

    private LongTermMemoryEntry findDuplicate(MemoryCandidate candidate, List<Float> candidateEmbedding) {
        String normalizedCandidate = normalizeForCompare(candidate.content());
        for (LongTermMemoryEntry entry : memories.values()) {
            if (!entry.isActive() || !candidate.category().equals(entry.getCategory())) {
                continue;
            }
            // 当前阶段只做“同类近重复合并”，不处理同一槽位的冲突仲裁，例如用户改名或默认地域变更。
            String normalizedEntry = normalizeForCompare(entry.getContent());
            if (normalizedEntry.equals(normalizedCandidate)
                    || normalizedEntry.contains(normalizedCandidate)
                    || normalizedCandidate.contains(normalizedEntry)) {
                return entry;
            }
            if (!candidateEmbedding.isEmpty()
                    && entry.getEmbedding() != null
                    && entry.getEmbedding().size() == candidateEmbedding.size()) {
                double similarity = cosine(candidateEmbedding, entry.getEmbedding());
                if (similarity >= memoryProperties.getLongTerm().getDedupThreshold()) {
                    return entry;
                }
            }
        }
        return null;
    }

    private void mergeIntoExisting(LongTermMemoryEntry entry,
                                   MemoryCandidate candidate,
                                   List<Float> embedding,
                                   long now) {
        if (candidate.content().length() > safe(entry.getContent()).length()) {
            entry.setContent(candidate.content());
        }
        entry.setImportance(Math.max(entry.getImportance(), candidate.importance()));
        entry.setConfidence(Math.max(entry.getConfidence(), candidate.confidence()));
        entry.setSource(mergeSource(entry.getSource(), candidate.source()));
        entry.setTags(mergeTags(entry.getTags(), candidate.tags()));
        if (entry.getEmbedding() == null || entry.getEmbedding().isEmpty()) {
            entry.setEmbedding(embedding);
        } else if (embedding != null && entry.getEmbedding().size() == embedding.size()) {
            entry.setEmbedding(weightedAverage(entry.getEmbedding(), embedding, entry.getImportance(), candidate.importance()));
        }
        if ((entry.getSessionId() == null || entry.getSessionId().isBlank()) && candidate.sessionId() != null) {
            entry.setSessionId(candidate.sessionId());
        }
        entry.setLastAccessed(now);
        entry.setVersion(entry.getVersion() + 1L);
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

    private boolean consolidateIfNeeded() {
        int triggerInterval = memoryProperties.getLongTerm().getConsolidationTriggerInterval();
        if (triggerInterval <= 0 || newMemoryCountSinceConsolidation < triggerInterval || memories.size() <= 1) {
            return false;
        }
        newMemoryCountSinceConsolidation = 0;
        return consolidateMemories();
    }

    private boolean consolidateMemories() {
        boolean changed = false;
        long now = System.currentTimeMillis();
        changed |= decayImportance(now);
        changed |= mergeSimilarMemories(now);
        changed |= expireLowValueMemories(now);
        if (changed) {
            logger.info("长期记忆治理完成，剩余 {} 条", memories.size());
        }
        return changed;
    }

    private boolean decayImportance(long now) {
        double decayRate = memoryProperties.getLongTerm().getDecayRate();
        if (decayRate <= 0D || decayRate >= 1D) {
            return false;
        }

        boolean changed = false;
        for (LongTermMemoryEntry entry : memories.values()) {
            if (!entry.isActive() || entry.getCreatedAt() <= 0) {
                continue;
            }
            double ageDays = Math.max(0D, (now - entry.getCreatedAt()) / 86_400_000D);
            double decayed = entry.getImportance() * Math.pow(decayRate, ageDays);
            if (entry.getImportance() - decayed >= 0.01D) {
                entry.setImportance(decayed);
                entry.setVersion(entry.getVersion() + 1L);
                changed = true;
            }
        }
        return changed;
    }

    private boolean mergeSimilarMemories(long now) {
        double dedupThreshold = memoryProperties.getLongTerm().getDedupThreshold();
        double similarityThreshold = memoryProperties.getLongTerm().getSimilarityThreshold();
        List<LongTermMemoryEntry> entries = new ArrayList<>(memories.values());
        Set<String> removedIds = new LinkedHashSet<>();
        boolean changed = false;

        for (int i = 0; i < entries.size(); i++) {
            LongTermMemoryEntry left = entries.get(i);
            if (removedIds.contains(left.getId()) || !left.isActive()) {
                continue;
            }
            for (int j = i + 1; j < entries.size(); j++) {
                LongTermMemoryEntry right = entries.get(j);
                if (removedIds.contains(right.getId())
                        || !right.isActive()
                        || !safe(left.getCategory()).equals(safe(right.getCategory()))) {
                    continue;
                }

                double similarity = memorySimilarity(left, right);
                if (similarity >= dedupThreshold) {
                    mergeDuplicateIntoLeft(left, right, now);
                    memories.remove(right.getId());
                    removedIds.add(right.getId());
                    changed = true;
                } else if (similarity >= similarityThreshold) {
                    LongTermMemoryEntry merged = mergeEntries(left, right, now);
                    memories.put(merged.getId(), merged);
                    memories.remove(right.getId());
                    removedIds.add(right.getId());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void mergeDuplicateIntoLeft(LongTermMemoryEntry left, LongTermMemoryEntry right, long now) {
        if (safe(right.getContent()).length() > safe(left.getContent()).length()) {
            left.setContent(right.getContent());
        }
        left.setImportance(Math.max(left.getImportance(), right.getImportance()));
        left.setConfidence(Math.max(left.getConfidence(), right.getConfidence()));
        left.setSource(mergeSource(left.getSource(), right.getSource()));
        left.setTags(mergeTags(left.getTags(), right.getTags()));
        if ((left.getSessionId() == null || left.getSessionId().isBlank()) && right.getSessionId() != null) {
            left.setSessionId(right.getSessionId());
        }
        if (left.getEmbedding() == null || left.getEmbedding().isEmpty()) {
            left.setEmbedding(right.getEmbedding());
        }
        left.setLastAccessed(now);
        left.setVersion(left.getVersion() + 1L);
    }

    private boolean expireLowValueMemories(long now) {
        int ttlDays = memoryProperties.getLongTerm().getTtlDays();
        if (ttlDays <= 0) {
            return false;
        }

        boolean changed = false;
        double minImportance = memoryProperties.getLongTerm().getMinImportance();
        long ttlMillis = ttlDays * 86_400_000L;
        for (LongTermMemoryEntry entry : new ArrayList<>(memories.values())) {
            if (!entry.isActive()) {
                continue;
            }
            if (now - entry.getCreatedAt() > ttlMillis && entry.getImportance() < minImportance) {
                memories.remove(entry.getId());
                changed = true;
            }
        }
        return changed;
    }

    private double memorySimilarity(LongTermMemoryEntry left, LongTermMemoryEntry right) {
        if (left.getEmbedding() != null
                && right.getEmbedding() != null
                && !left.getEmbedding().isEmpty()
                && left.getEmbedding().size() == right.getEmbedding().size()) {
            return cosine(left.getEmbedding(), right.getEmbedding());
        }
        return tfSimilarity(left.getContent(), right.getContent());
    }

    private LongTermMemoryEntry mergeEntries(LongTermMemoryEntry left, LongTermMemoryEntry right, long now) {
        double leftImportance = left.getImportance();
        double rightImportance = right.getImportance();

        left.setContent(mergeContent(left.getContent(), right.getContent()));
        left.setImportance(Math.max(leftImportance, rightImportance));
        left.setConfidence(Math.max(left.getConfidence(), right.getConfidence()));
        left.setSource(mergeSource(left.getSource(), right.getSource()));
        left.setTags(mergeTags(left.getTags(), right.getTags()));
        if ((left.getSessionId() == null || left.getSessionId().isBlank()) && right.getSessionId() != null) {
            left.setSessionId(right.getSessionId());
        }
        if (left.getEmbedding() != null
                && right.getEmbedding() != null
                && !left.getEmbedding().isEmpty()
                && left.getEmbedding().size() == right.getEmbedding().size()) {
            left.setEmbedding(weightedAverage(left.getEmbedding(), right.getEmbedding(), leftImportance, rightImportance));
        }
        left.setLastAccessed(now);
        left.setVersion(left.getVersion() + 1L);
        return left;
    }

    private String mergeContent(String base, String other) {
        String normalizedBase = safe(base);
        String normalizedOther = safe(other);
        if (normalizeForCompare(normalizedBase).contains(normalizeForCompare(normalizedOther))) {
            return normalizedBase.length() >= normalizedOther.length() ? normalizedBase : normalizedOther;
        }
        if (normalizeForCompare(normalizedOther).contains(normalizeForCompare(normalizedBase))) {
            return normalizedOther.length() >= normalizedBase.length() ? normalizedOther : normalizedBase;
        }
        return normalizeContent(normalizedBase + "；" + normalizedOther);
    }

    private double semanticSimilarity(String query, List<Float> queryEmbedding, LongTermMemoryEntry entry) {
        if (!queryEmbedding.isEmpty()
                && entry.getEmbedding() != null
                && entry.getEmbedding().size() == queryEmbedding.size()) {
            return cosine(queryEmbedding, entry.getEmbedding());
        }
        // 与 AGI-saber 一样，embedding 不可用或维度不匹配时降级到 TF cosine；TF 词表由内容临时重建，无需单独持久化。
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

    private List<Float> weightedAverage(List<Float> left, List<Float> right, double leftWeight, double rightWeight) {
        if (left == null || right == null || left.size() != right.size() || left.isEmpty()) {
            return left == null ? List.of() : new ArrayList<>(left);
        }
        double totalWeight = Math.max(0D, leftWeight) + Math.max(0D, rightWeight);
        if (totalWeight <= 0D) {
            return new ArrayList<>(left);
        }

        List<Float> averaged = new ArrayList<>(left.size());
        for (int i = 0; i < left.size(); i++) {
            double value = (left.get(i) * leftWeight + right.get(i) * rightWeight) / totalWeight;
            averaged.add((float) value);
        }
        return averaged;
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

    private LongTermMemorySource mergeSource(LongTermMemorySource existing, LongTermMemorySource incoming) {
        if (existing == LongTermMemorySource.LLM || incoming == null) {
            return existing;
        }
        if (incoming == LongTermMemorySource.LLM) {
            return incoming;
        }
        if (existing == null
                || existing == LongTermMemorySource.LEGACY
                || existing == LongTermMemorySource.BOOTSTRAP) {
            return incoming;
        }
        return existing;
    }

    private List<String> mergeTags(List<String> existing, List<String> incoming) {
        Set<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            merged.addAll(existing);
        }
        if (incoming != null) {
            merged.addAll(incoming);
        }
        return new ArrayList<>(merged);
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

    private record MemoryCandidate(
            String sessionId,
            String category,
            String content,
            List<String> tags,
            double importance,
            double confidence,
            LongTermMemorySource source
    ) {
        private MemoryCandidate {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }
}

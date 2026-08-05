package com.leap.agent.domain.memory.longterm;

import com.leap.agent.common.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆治理组件，集中处理去重、合并、衰减和淘汰。
 */
@Service
public class LongTermMemoryConsolidationService {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryConsolidationService.class);
    private static final int MAX_CONTENT_LENGTH = 180;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[a-z0-9][a-z0-9\\-]{1,}");

    private final MemoryProperties memoryProperties;
    private final Neo4jMemoryGraphClient memoryGraphClient;

    /**
     * 注入长期记忆治理配置和图投影客户端。
     */
    public LongTermMemoryConsolidationService(MemoryProperties memoryProperties,
                                              Neo4jMemoryGraphClient memoryGraphClient) {
        this.memoryProperties = memoryProperties;
        this.memoryGraphClient = memoryGraphClient;
    }

    /**
     * 将候选记忆写入已有集合，必要时合并到旧记忆。
     */
    public UpsertResult upsertCandidate(String ownerId,
                                        Map<String, LongTermMemoryEntry> memories,
                                        LongTermMemoryCandidate candidate,
                                        List<Float> embedding,
                                        String previousMemoryId) {
        if (candidate == null || memories == null || candidate.content() == null || candidate.content().isBlank()) {
            return new UpsertResult(false, false);
        }
        LongTermMemoryEntry duplicate = findDuplicate(memories, candidate, embedding);
        long now = System.currentTimeMillis();
        if (duplicate != null) {
            mergeCandidateIntoExisting(duplicate, candidate, embedding, now);
            memoryGraphClient.upsertMemory(ownerId, duplicate);
            return new UpsertResult(true, false);
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
        indexNewMemoryInGraph(ownerId, memories, entry, previousMemoryId);
        logger.info("新增长期记忆: [{}] {}", entry.getCategory(), entry.getContent());
        return new UpsertResult(true, true);
    }

    /**
     * 按触发间隔执行长期记忆治理。
     */
    public ConsolidationResult consolidateIfNeeded(String ownerId,
                                                   Map<String, LongTermMemoryEntry> memories,
                                                   int newMemoryCountSinceConsolidation) {
        int triggerInterval = memoryProperties.getLongTerm().getConsolidationTriggerInterval();
        if (triggerInterval <= 0
                || newMemoryCountSinceConsolidation < triggerInterval
                || memories == null
                || memories.size() <= 1) {
            return new ConsolidationResult(false, newMemoryCountSinceConsolidation);
        }
        return new ConsolidationResult(consolidateMemories(ownerId, memories), 0);
    }

    /**
     * 查找应被合并的同类旧记忆。
     */
    private LongTermMemoryEntry findDuplicate(Map<String, LongTermMemoryEntry> memories,
                                              LongTermMemoryCandidate candidate,
                                              List<Float> candidateEmbedding) {
        String normalizedCandidate = normalizeForCompare(candidate.content());
        for (LongTermMemoryEntry entry : memories.values()) {
            if (!entry.isActive() || !candidate.category().equals(entry.getCategory())) {
                continue;
            }
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

    /**
     * 将候选记忆融合进已存在的长期记忆。
     */
    private void mergeCandidateIntoExisting(LongTermMemoryEntry entry,
                                            LongTermMemoryCandidate candidate,
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

    /**
     * 执行一次完整的衰减、相似合并和 TTL 淘汰。
     */
    private boolean consolidateMemories(String ownerId, Map<String, LongTermMemoryEntry> memories) {
        boolean changed = false;
        long now = System.currentTimeMillis();
        changed |= decayImportance(memories, now);
        changed |= mergeSimilarMemories(ownerId, memories, now);
        changed |= expireLowValueMemories(ownerId, memories, now);
        if (changed) {
            logger.info("长期记忆治理完成，剩余 {} 条", memories.size());
        }
        return changed;
    }

    /**
     * 按年龄对长期记忆重要性做指数衰减。
     */
    private boolean decayImportance(Map<String, LongTermMemoryEntry> memories, long now) {
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

    /**
     * 合并同类别中高度相似的长期记忆。
     */
    private boolean mergeSimilarMemories(String ownerId, Map<String, LongTermMemoryEntry> memories, long now) {
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
                    memoryGraphClient.upsertMemory(ownerId, left);
                    memoryGraphClient.deleteMemory(ownerId, right.getId());
                    removedIds.add(right.getId());
                    changed = true;
                } else if (similarity >= similarityThreshold) {
                    LongTermMemoryEntry merged = mergeEntries(left, right, now);
                    memories.put(merged.getId(), merged);
                    memories.remove(right.getId());
                    memoryGraphClient.upsertMemory(ownerId, merged);
                    memoryGraphClient.deleteMemory(ownerId, right.getId());
                    removedIds.add(right.getId());
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * 将重复记忆的元数据融合到左侧记忆。
     */
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

    /**
     * 淘汰超过 TTL 且重要性低的记忆，图中心节点会被保护。
     */
    private boolean expireLowValueMemories(String ownerId, Map<String, LongTermMemoryEntry> memories, long now) {
        int ttlDays = memoryProperties.getLongTerm().getTtlDays();
        if (ttlDays <= 0) {
            return false;
        }

        boolean changed = false;
        double minImportance = memoryProperties.getLongTerm().getMinImportance();
        long ttlMillis = ttlDays * 86_400_000L;
        List<LongTermMemoryEntry> expiryCandidates = new ArrayList<>();
        for (LongTermMemoryEntry entry : new ArrayList<>(memories.values())) {
            if (!entry.isActive()) {
                continue;
            }
            if (now - entry.getCreatedAt() > ttlMillis && entry.getImportance() < minImportance) {
                expiryCandidates.add(entry);
            }
        }
        Set<String> protectedIds = graphProtectedMemoryIds(ownerId, expiryCandidates);
        for (LongTermMemoryEntry entry : expiryCandidates) {
            if (protectedIds.contains(entry.getId())) {
                continue;
            }
            if (memories.remove(entry.getId()) != null) {
                memoryGraphClient.deleteMemory(ownerId, entry.getId());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 将新增记忆写入图投影，并补充时序边和相似边。
     */
    private void indexNewMemoryInGraph(String ownerId,
                                       Map<String, LongTermMemoryEntry> memories,
                                       LongTermMemoryEntry newEntry,
                                       String previousMemoryId) {
        if (!memoryGraphClient.isAvailable() || newEntry == null) {
            return;
        }

        memoryGraphClient.upsertMemory(ownerId, newEntry);
        if (previousMemoryId != null) {
            memoryGraphClient.addFollows(ownerId, previousMemoryId, newEntry.getId());
        }
        linkSimilarGraphEdges(ownerId, memories, newEntry);
    }

    /**
     * 扫描近期 active 记忆，为新记忆建立 SIMILAR_TO 边。
     */
    private void linkSimilarGraphEdges(String ownerId,
                                       Map<String, LongTermMemoryEntry> memories,
                                       LongTermMemoryEntry newEntry) {
        List<Float> newEmbedding = newEntry.getEmbedding();
        if (newEmbedding == null || newEmbedding.isEmpty()) {
            return;
        }

        int limit = Math.max(0, memoryProperties.getLongTerm().getGraph().getSimilarScanLimit());
        if (limit == 0) {
            return;
        }
        double threshold = memoryProperties.getLongTerm().getGraph().getSimilarityEdgeThreshold();

        memories.values().stream()
                .filter(entry -> entry != null
                        && entry.isActive()
                        && entry.getId() != null
                        && !entry.getId().equals(newEntry.getId())
                        && entry.getEmbedding() != null
                        && entry.getEmbedding().size() == newEmbedding.size())
                .sorted(Comparator.comparingLong(LongTermMemoryEntry::getCreatedAt).reversed())
                .limit(limit)
                .forEach(entry -> {
                    double similarity = cosine(entry.getEmbedding(), newEmbedding);
                    if (similarity >= threshold) {
                        memoryGraphClient.addSimilar(ownerId, entry.getId(), newEntry.getId(), similarity);
                    }
                });
    }

    /**
     * 询问 Neo4j 哪些淘汰候选属于高中心度保护节点。
     */
    private Set<String> graphProtectedMemoryIds(String ownerId, List<LongTermMemoryEntry> candidates) {
        if (!memoryGraphClient.isAvailable() || candidates == null || candidates.isEmpty()) {
            return Set.of();
        }
        int threshold = memoryProperties.getLongTerm().getGraph().getCentralityProtectInDegree();
        if (threshold <= 0) {
            return Set.of();
        }
        List<String> candidateIds = candidates.stream()
                .map(LongTermMemoryEntry::getId)
                .toList();
        return memoryGraphClient.findHighCentralityMemoryIds(ownerId, candidateIds, threshold);
    }

    /**
     * 计算两条记忆的语义相似度，embedding 不可用时退化到 TF。
     */
    private double memorySimilarity(LongTermMemoryEntry left, LongTermMemoryEntry right) {
        if (left.getEmbedding() != null
                && right.getEmbedding() != null
                && !left.getEmbedding().isEmpty()
                && left.getEmbedding().size() == right.getEmbedding().size()) {
            return cosine(left.getEmbedding(), right.getEmbedding());
        }
        return tfSimilarity(left.getContent(), right.getContent());
    }

    /**
     * 将两条相似记忆融合成左侧记忆。
     */
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

    /**
     * 合并两条记忆正文。
     */
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

    /**
     * 计算两个 embedding 的余弦相似度。
     */
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

    /**
     * 按重要性加权平均两组 embedding。
     */
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

    /**
     * 用词频向量计算文本相似度。
     */
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

    /**
     * 将文本切成轻量 TF token。
     */
    private Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> tokens = new HashMap<>();
        Matcher matcher = TOKEN_PATTERN.matcher(safe(text).toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.merge(matcher.group(), 1, Integer::sum);
        }
        return tokens;
    }

    /**
     * 合并两条记忆来源。
     */
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

    /**
     * 合并两组标签并去重。
     */
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

    /**
     * 归一化用于相似比较的文本。
     */
    private String normalizeForCompare(String content) {
        return safe(content)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s，。！？；：、“”‘’（）()【】\\[\\]]+", "");
    }

    /**
     * 压缩正文长度，避免合并后无限膨胀。
     */
    private String normalizeContent(String content) {
        String normalized = compact(content);
        if (normalized.length() > MAX_CONTENT_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTENT_LENGTH) + "...";
        }
        return normalized;
    }

    /**
     * 压缩文本空白。
     */
    private String compact(String text) {
        return safe(text).replaceAll("\\s+", " ").trim();
    }

    /**
     * 处理空字符串。
     */
    private String safe(String text) {
        return text == null ? "" : text;
    }

    public record UpsertResult(boolean changed, boolean addedNewMemory) {
    }

    public record ConsolidationResult(boolean changed, int newMemoryCountSinceConsolidation) {
    }
}

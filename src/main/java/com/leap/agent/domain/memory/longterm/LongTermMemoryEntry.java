package com.leap.agent.domain.memory.longterm;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆条目。
 *
 * <p>这里显式增加 sessionId、confidence、
 * status 和 source，方便后续做调试、人工治理和数据库迁移。</p>
 */
public class LongTermMemoryEntry {

    private String id;
    private String sessionId;
    private String category = LongTermMemoryCategory.GENERAL.value();
    private String content;
    private List<String> tags = new ArrayList<>();
    private double importance;
    private double confidence;
    private LongTermMemorySource source = LongTermMemorySource.LEGACY;
    private List<Float> embedding = new ArrayList<>();
    private long createdAt;
    private long lastAccessed;
    private long version = 1L;
    private LongTermMemoryStatus status = LongTermMemoryStatus.ACTIVE;
    /**
     * 最近一次召回时计算出的临时分数，只用于调试视图，不属于稳定记忆内容。
     */
    private double score;

    public LongTermMemoryEntry() {
    }

    public LongTermMemoryEntry(String id,
                               String sessionId,
                               String category,
                               String content,
                               List<String> tags,
                               double importance,
                               double confidence,
                               LongTermMemorySource source,
                               List<Float> embedding,
                               long createdAt,
                               long lastAccessed,
                               long version) {
        this.id = id;
        this.sessionId = sessionId;
        setCategory(category);
        this.content = content;
        setTags(tags);
        this.importance = importance;
        this.confidence = confidence;
        setSource(source);
        setEmbedding(embedding);
        this.createdAt = createdAt;
        this.lastAccessed = lastAccessed;
        this.version = version > 0 ? version : 1L;
    }

    public boolean isActive() {
        return status == LongTermMemoryStatus.ACTIVE;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = LongTermMemoryCategory.fromValue(category)
                .map(LongTermMemoryCategory::value)
                .orElse(LongTermMemoryCategory.GENERAL.value());
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public double getImportance() {
        return importance;
    }

    public void setImportance(double importance) {
        this.importance = importance;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public LongTermMemorySource getSource() {
        return source;
    }

    public void setSource(LongTermMemorySource source) {
        this.source = source == null ? LongTermMemorySource.LEGACY : source;
    }

    public List<Float> getEmbedding() {
        return embedding;
    }

    public void setEmbedding(List<Float> embedding) {
        this.embedding = embedding == null ? new ArrayList<>() : new ArrayList<>(embedding);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastAccessed() {
        return lastAccessed;
    }

    public void setLastAccessed(long lastAccessed) {
        this.lastAccessed = lastAccessed;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version > 0 ? version : 1L;
    }

    public LongTermMemoryStatus getStatus() {
        return status;
    }

    public void setStatus(LongTermMemoryStatus status) {
        this.status = status == null ? LongTermMemoryStatus.ACTIVE : status;
    }

    @JsonIgnore
    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}

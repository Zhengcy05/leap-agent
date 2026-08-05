package com.leap.agent.common.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索与持久化配置。
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private int topK = 3;

    private String model = "qwen3-max";

    private final Postgres postgres = new Postgres();

    private final Elasticsearch elasticsearch = new Elasticsearch();

    private final Retrieval retrieval = new Retrieval();

    private final KnowledgeGraph knowledgeGraph = new KnowledgeGraph();

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public void setModel(String model) {
        this.model = model;
    }

    @Getter
    public static class Postgres {
        private String jdbcUrl = "jdbc:postgresql://localhost:5432/leap_agent";

        private String username = "leap";

        private String password = "leap";

        private boolean initSchema = true;

        private String chunkTableName = "rag_document_chunk";

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public void setInitSchema(boolean initSchema) {
            this.initSchema = initSchema;
        }

        public void setChunkTableName(String chunkTableName) {
            this.chunkTableName = chunkTableName;
        }
    }

    @Getter
    public static class Elasticsearch {
        /**
         * 是否启用 ES 关键词投影索引。
         */
        private boolean enabled = true;

        private String baseUrl = "http://localhost:9200";

        private String indexName = "leap_rag_chunks";

        private int timeoutMillis = 5000;

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public void setIndexName(String indexName) {
            this.indexName = indexName;
        }

        public void setTimeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }
    }

    @Getter
    public static class Retrieval {
        /**
         * Reciprocal Rank Fusion 的平滑常量；0 表示沿用旧 KG 配置或默认值。
         */
        private int rrfK;

        /**
         * RRF 候选池相对 topK 的放大倍数；0 表示沿用旧 KG 配置或默认值。
         */
        private int fetchMultiplier;

        /**
         * 是否启用 RRF 后的精排。
         */
        private boolean rerankEnabled = true;

        /**
         * rerank 提供方：dashscope-rerank、llm 或 none。
         */
        private String rerankProvider = "dashscope-rerank";

        /**
         * rerank 开启时的候选池放大倍数。
         */
        private int rerankPoolMultiplier = 4;

        /**
         * 候选数量达到该阈值才触发 rerank。
         */
        private int minRerankCandidates = 20;

        /**
         * 每条候选给 reranker 看的最大字符数。
         */
        private int rerankPreviewLen = 200;

        /**
         * rerank 使用的模型；专用 rerank 为空时用 qwen3-vl-rerank，LLM 为空时复用 rag.model。
         */
        private String rerankModel = "qwen3-vl-rerank";

        /**
         * DashScope API 基础地址。
         */
        private String rerankBaseUrl = "https://dashscope.aliyuncs.com/api/v1";

        /**
         * rerank 请求超时时间。
         */
        private int rerankTimeoutMillis = 8000;

        /**
         * 专用 rerank 失败时是否允许退到 LLM listwise。
         */
        private boolean rerankLlmFallbackEnabled = true;

        /**
         * 设置 RRF 平滑常量。
         */
        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        /**
         * 设置 RRF 候选池放大倍数。
         */
        public void setFetchMultiplier(int fetchMultiplier) {
            this.fetchMultiplier = fetchMultiplier;
        }

        /**
         * 设置是否启用 rerank。
         */
        public void setRerankEnabled(boolean rerankEnabled) {
            this.rerankEnabled = rerankEnabled;
        }

        /**
         * 设置 rerank 提供方。
         */
        public void setRerankProvider(String rerankProvider) {
            this.rerankProvider = rerankProvider;
        }

        /**
         * 设置 rerank 候选池放大倍数。
         */
        public void setRerankPoolMultiplier(int rerankPoolMultiplier) {
            this.rerankPoolMultiplier = rerankPoolMultiplier;
        }

        /**
         * 设置触发 rerank 的最小候选数量。
         */
        public void setMinRerankCandidates(int minRerankCandidates) {
            this.minRerankCandidates = minRerankCandidates;
        }

        /**
         * 设置每条候选的预览长度。
         */
        public void setRerankPreviewLen(int rerankPreviewLen) {
            this.rerankPreviewLen = rerankPreviewLen;
        }

        /**
         * 设置 rerank 使用的模型。
         */
        public void setRerankModel(String rerankModel) {
            this.rerankModel = rerankModel;
        }

        /**
         * 设置 DashScope API 基础地址。
         */
        public void setRerankBaseUrl(String rerankBaseUrl) {
            this.rerankBaseUrl = rerankBaseUrl;
        }

        /**
         * 设置 rerank 请求超时时间。
         */
        public void setRerankTimeoutMillis(int rerankTimeoutMillis) {
            this.rerankTimeoutMillis = rerankTimeoutMillis;
        }

        /**
         * 设置专用 rerank 失败时是否退到 LLM。
         */
        public void setRerankLlmFallbackEnabled(boolean rerankLlmFallbackEnabled) {
            this.rerankLlmFallbackEnabled = rerankLlmFallbackEnabled;
        }
    }

    @Getter
    public static class KnowledgeGraph {
        /**
         * 是否启用 Neo4j RAG 知识图谱投影。
         */
        private boolean enabled = true;

        private String uri = "bolt://localhost:7687";

        private String username = "neo4j";

        private String password = "leap";

        /**
         * 是否启动时幂等创建 KG 节点约束/索引。
         */
        private boolean initSchema = true;

        /**
         * 图召回最大跳数；实现侧会做上限保护。
         */
        private int maxHops = 2;

        /**
         * KG 在 RRF 融合中的权重。
         */
        private double weight = 1.0D;

        /**
         * 每路召回候选池相对 topK 的放大倍数。
         */
        private int fetchMultiplier = 2;

        /**
         * Reciprocal Rank Fusion 的平滑常量。
         */
        private int rrfK = 60;

        /**
         * 是否异步抽取实体关系并写入 Neo4j。
         */
        private boolean asyncIndexEnabled = true;

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public void setInitSchema(boolean initSchema) {
            this.initSchema = initSchema;
        }

        public void setMaxHops(int maxHops) {
            this.maxHops = maxHops;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        public void setFetchMultiplier(int fetchMultiplier) {
            this.fetchMultiplier = fetchMultiplier;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public void setAsyncIndexEnabled(boolean asyncIndexEnabled) {
            this.asyncIndexEnabled = asyncIndexEnabled;
        }
    }
}

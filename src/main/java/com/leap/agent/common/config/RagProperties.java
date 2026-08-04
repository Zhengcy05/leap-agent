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
}

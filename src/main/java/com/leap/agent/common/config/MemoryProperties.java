package com.leap.agent.common.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆模块配置。
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    // 三层记忆都挂在 memory 前缀下，避免 chat 主链路依赖具体存储实现。
    private final ShortTerm shortTerm = new ShortTerm();
    private final Preference preference = new Preference();
    private final LongTerm longTerm = new LongTerm();

    @Getter
    public static class ShortTerm {
        /**
         * 短期记忆保留的最大消息对数。
         */
        private int maxWindowSize = 6;

        public void setMaxWindowSize(int maxWindowSize) {
            this.maxWindowSize = maxWindowSize;
        }
    }

    @Getter
    public static class Preference {
        /**
         * 偏好持久化文件路径。
         * 当前默认是本地 JSON 文件，后续若切数据库可保留该配置作为本地开发兜底。
         */
        private String storagePath = "./data/preferences.json";

        /**
         * 是否启用回复后的异步 LLM 偏好抽取。
         */
        private boolean asyncLlmEnabled = true;

        public void setStoragePath(String storagePath) {
            this.storagePath = storagePath;
        }

        public void setAsyncLlmEnabled(boolean asyncLlmEnabled) {
            this.asyncLlmEnabled = asyncLlmEnabled;
        }
    }

    @Getter
    public static class LongTerm {
        /**
         * 长期记忆仓储类型：file 或 postgres。
         */
        private String repository = "file";

        /**
         * 文件仓储持久化路径，仅在 repository=file 时使用。
         */
        private String storagePath = "./data/long-term-memory.json";

        private final Postgres postgres = new Postgres();
        private final Graph graph = new Graph();

        /**
         * 是否启用对话后的长期记忆异步抽取。
         */
        private boolean asyncExtractionEnabled = true;

        /**
         * 是否启用 LLM 抽取。长期记忆不做规则写入，避免和偏好记忆的强 schema 抽取重叠。
         */
        private boolean asyncLlmEnabled = true;

        /**
         * 注入 prompt 的长期记忆最大条数。
         */
        private int promptTopK = 4;

        /**
         * 进入 prompt 的最低召回分数。
         */
        private double minRecallScore = 0.42D;

        /**
         * LLM 抽取条目的最低置信度。
         */
        private double minConfidence = 0.7D;

        /**
         * 同类记忆去重阈值；向量相似度超过该值时合并而不是新增。
         */
        private double dedupThreshold = 0.92D;

        /**
         * 同类记忆相似合并阈值；低于去重阈值但超过该值时会融合成一条更完整的记忆。
         */
        private double similarityThreshold = 0.80D;

        /**
         * 低重要性记忆超过该天数后会被淘汰；0 表示不过期。
         */
        private int ttlDays = 30;

        /**
         * 每日重要性衰减系数。
         */
        private double decayRate = 0.995D;

        /**
         * 过期淘汰时的最低重要性保护线。
         */
        private double minImportance = 0.30D;

        /**
         * 每新增多少条长期记忆触发一次合并/衰减/淘汰。
         */
        private int consolidationTriggerInterval = 5;

        @Getter
        public static class Postgres {
            /**
             * PostgreSQL JDBC URL；长期记忆作为事实源时使用。
             */
            private String jdbcUrl = "jdbc:postgresql://localhost:5432/leap_agent";

            private String username = "leap";

            private String password = "leap";

            /**
             * 是否由应用启动时创建/迁移轻量表结构。
             */
            private boolean initSchema = true;

            /**
             * 长期记忆表名。仅支持普通标识符，避免动态 SQL 注入。
             */
            private String tableName = "long_term_memory";

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

            public void setTableName(String tableName) {
                this.tableName = tableName;
            }
        }

        @Getter
        public static class Graph {
            /**
             * 是否启用 Neo4j 图增强。关闭后长期记忆退化为纯 PG/内存召回。
             */
            private boolean enabled = true;

            /**
             * Neo4j Bolt 地址。
             */
            private String uri = "bolt://localhost:7687";

            private String username = "neo4j";

            private String password = "leap";

            /**
             * 是否启动时幂等创建 Memory 节点约束/索引。
             */
            private boolean initSchema = true;

            /**
             * 新记忆与近期记忆超过该相似度时建立 SIMILAR_TO 边。
             */
            private double similarityEdgeThreshold = 0.80D;

            /**
             * 新增记忆时向前扫描多少条 active 记忆来建立相似边。
             */
            private int similarScanLimit = 50;

            /**
             * 召回 seed 通过图扩展的最大跳数。
             */
            private int neighborHops = 1;

            /**
             * 入度达到该值的记忆在过期淘汰时受保护。
             */
            private int centralityProtectInDegree = 3;

            /**
             * 图扩展命中的记忆进入排序时使用的兜底分数。
             */
            private double graphExpandedScore = 0.45D;

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

            public void setSimilarityEdgeThreshold(double similarityEdgeThreshold) {
                this.similarityEdgeThreshold = similarityEdgeThreshold;
            }

            public void setSimilarScanLimit(int similarScanLimit) {
                this.similarScanLimit = similarScanLimit;
            }

            public void setNeighborHops(int neighborHops) {
                this.neighborHops = neighborHops;
            }

            public void setCentralityProtectInDegree(int centralityProtectInDegree) {
                this.centralityProtectInDegree = centralityProtectInDegree;
            }

            public void setGraphExpandedScore(double graphExpandedScore) {
                this.graphExpandedScore = graphExpandedScore;
            }
        }
    }
}

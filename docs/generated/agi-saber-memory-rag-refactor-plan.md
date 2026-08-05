# Leap Agent 参考成品项目的 Memory/RAG 重构记录

## 目标架构

Leap Agent 参考成品项目的职责拆分，将“事实源”和“检索投影”分开：

1. PostgreSQL 是 RAG 分片和长期记忆的权威事实源。
2. Milvus 是可重建的语义向量索引。
3. Elasticsearch 是可重建的关键词 / BM25 投影索引。
4. Neo4j 是可重建的知识图谱和记忆图投影层。
5. 进程内缓存只承载运行期快照、召回排序和异步治理协调。

因此，RAG 分片和长期记忆都不再依赖 Milvus 或本地 JSON 作为唯一持久化副本。Milvus、Elasticsearch、Neo4j 出现陈旧数据时，查询必须回 PostgreSQL 回表读取，并以 PostgreSQL 的 `status`、`version`、`content_hash` 为准。

## 当前落地状态

1. RAG 入库链路已调整为 `PostgreSQL upsert -> Milvus 向量投影 -> Elasticsearch BM25 投影 -> Neo4j KG 投影`。
2. RAG 查询链路已调整为 `Milvus + ES + Neo4j KG 三路候选 -> RRF 融合 -> PostgreSQL 回表读取 active 分片 -> DashScope 专用 rerank -> topK`。
3. RAG rerank 只有在 PG 回表后候选池达到 `min-rerank-candidates=20` 时才触发，专用 rerank 失败时可退到 LLM 列表式重排，最终再退回 RRF 顺序。
4. 长期记忆推荐使用 PostgreSQL 作为事实源，file 仓储仅作为轻量回退。
5. 长期记忆写入仅使用 LLM 异步抽取，不再用规则抽取写入长期记忆，避免与偏好记忆的强 schema 槽位重叠。
6. 长期记忆图增强已落地：Neo4j 维护 Memory 节点、FOLLOWS / SIMILAR_TO 边、图扩展召回和高中心度 TTL 保护。
7. 长期记忆治理已从抽取/召回服务中独立出来，由 `LongTermMemoryConsolidationService` 专管候选去重、语义合并、importance 衰减、TTL 淘汰、图保护和相似边创建。

## 重构规则

1. RAG 入库必须先写 PostgreSQL，再以尽力而为方式写 Milvus、Elasticsearch 和 Neo4j。
2. Milvus、Elasticsearch、Neo4j 都只保存可重建投影，不作为提示词注入内容的权威来源。
3. RAG 查询阶段只从投影层拿候选 id，最终内容必须回 PostgreSQL 读取 active 分片。
4. PostgreSQL RAG 分片行需要携带 `version`、`content_hash`、`status`、时间戳等治理字段。
5. 删除旧文档时先在 PostgreSQL 标记删除，再尽力删除 Milvus、Elasticsearch 和 Neo4j 投影。
6. 长期记忆写入时先做候选置信度过滤和本轮精确去重，再进入治理组件。
7. 长期记忆治理组件负责跨轮去重、语义合并、衰减和淘汰；图中心度保护必须在删除前参与决策。
8. Neo4j 图关系写入失败只记录日志，不回滚 PostgreSQL 写入。

## 本轮已实现

1. 基于 PostgreSQL 的 RAG 分片仓储。
2. RAG 分片表结构的本地初始化。
3. RAG 写入链路改为 PostgreSQL 事实源优先，再写 Milvus 向量投影。
4. Elasticsearch 关键词 / BM25 投影索引。
5. RAG 查询改为 Milvus、Elasticsearch、Neo4j KG 三路召回，RRF 融合后回 PostgreSQL 读取权威内容。
6. RAG rerank 层：PG 回表后候选数至少 20 条才调用 DashScope 专用 rerank，支持 LLM 回退和 RRF 回退。
7. Neo4j RAG Knowledge Graph 投影：分片实体关系抽取、KG 图召回、与 Milvus/ES 的 RRF 融合。
8. 长期记忆仅 LLM 抽取：抽取范围收窄到长期事实、排障案例、工具经验和用户明确约束，避开回复语言、地域、时间范围等偏好槽位。
9. Neo4j 长期记忆图投影：Memory 节点、FOLLOWS / SIMILAR_TO 边、图扩展召回和中心度保护。
10. 长期记忆治理组件：去重、语义合并、importance 衰减、TTL 淘汰、图中心度保护、SIMILAR_TO 边创建从 `LongTermMemoryService` 中独立出来。
11. 本地 compose、配置样例、README 文档已更新到 PostgreSQL、Elasticsearch、Neo4j、RRF、rerank 和长期记忆治理的新架构。

## 暂缓事项

1. 持久化 outbox 表和独立后台索引 worker。
2. 用 pgvector 替代或补充 Milvus 的方案评估。
3. 跨 PostgreSQL、Milvus、Elasticsearch、Neo4j 的分布式事务保证。
4. 长期记忆图中 CAUSES / BELONGS_TO 边的 LLM 关系抽取。
5. 查询改写 / 多查询的二级 RRF 合并。

## 一致性模型

当前一致性模型是“PostgreSQL 强一致事实源 + 投影层最终一致”：

1. PostgreSQL 是 RAG 分片和长期记忆的权威事实源。
2. Milvus、Elasticsearch、Neo4j 都是可重建投影。
3. 查询命中投影层后，必须回 PostgreSQL 回表读取，只有 active 数据能进入 rerank 和提示词。
4. 投影层中的陈旧命中通过 PostgreSQL `status` 和 `version` 过滤。
5. 索引重建可以通过扫描 PostgreSQL 完成。
6. Neo4j RAG KG 只贡献候选分片 id，不承载权威分片内容。
7. Neo4j 长期记忆图只贡献关联扩展和治理保护信息，不替代 PostgreSQL 中的长期记忆内容。

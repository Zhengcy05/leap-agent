# AGI-saber 记忆模块代码解析与整体评价

本文整理自围绕项目“三层记忆系统”和“图增强记忆”的两轮分析，重点回答两个问题：

- 短期记忆之外，长期记忆、用户偏好、图增强记忆是如何融入 Agent 的？
- 这个记忆模块整体实现质量如何？

## 一、记忆如何融入 Agent

整体看，这个项目里的长期记忆、偏好记忆、图增强记忆本质上不是直接改 Agent 状态机，而是围绕 Agent 做三件事：

1. 请求前召回成 prompt 上下文。
2. 工具调用和规划时影响参数、计划。
3. 回复后异步写回，并触发去重、合并、衰减、淘汰。

主调用链在 `src/main/java/com/agi/assistant/service/agent/UnifiedAgentService.java`。

每轮请求进入 `processInternal` 后，大致流程是：

```text
stm.add("user", query);
infra.saveChatHistory("user", query);

runAsyncPreferenceExtraction(query);
String[] extracted = pref.extractAndSave(query);

String memPrefix = buildMemorySystemPrefixWithCtx(query);
List<Map<String, String>> histMsgs = ChatHistoryAdapter.buildHistory(stm, query);
```

之后通过 `ChatRouter.decideMode(...)` 选择 `chat / tool / rag / react`，不同模式都会不同程度使用这份 `memPrefix`。

回复完成后：

```text
stm.add("assistant", resp.getAnswer());
infra.saveChatHistory("assistant", resp.getAnswer());
memoryWriter.writeAfterReply(query, resp.getAnswer());
```

也就是说，记忆系统的核心闭环是：

```text
用户输入
 -> 写短期记忆
 -> 抽取偏好
 -> 召回长期/图记忆
 -> 拼进 system prompt
 -> Agent 生成或调用工具
 -> 回复后异步抽取长期记忆
 -> 写 LTM / Preference / Neo4j / PG
 -> 后续对话再召回
```

## 二、长期记忆

长期记忆实现位于：

- `src/main/java/com/agi/assistant/service/memory/LongTermMemory.java`
- `src/main/java/com/agi/assistant/model/MemoryItem.java`

每条长期记忆是一个 `MemoryItem`，核心字段包括：

```text
private int id;
private String content;
private double importance;
private List<Double> embedding;
private double score;
private LocalDateTime createdAt;
private LocalDateTime lastAccessed;
private String category = "general";
private List<String> tags = new ArrayList<>();
private String slotHint;
```

其中 `category / tags / slotHint` 是为了支持后续按类型装配上下文，例如：

- `identity`
- `preference`
- `tool_failure`
- `policy`
- `general`

长期记忆真正融入 Agent 的地方是 `buildMemorySystemPrefixWithCtx`：

```text
List<Double> queryEmb = llm.embed(query);
List<MemoryItem> recalled = (graphMem != null
        ? graphMem.recall(query, cfg.getMemory().getLongTermTopK(), queryEmb)
        : ltm.recall(query, cfg.getMemory().getLongTermTopK(), queryEmb));
if (!recalled.isEmpty()) {
    List<String> contents = recalled.stream().map(MemoryItem::getContent).toList();
    parts.add("【相关记忆】\n" + String.join("\n", contents));
}
```

所以长期记忆对 Agent 的影响方式是：**先按当前 query 召回相关记忆，再把结果作为 `【相关记忆】` 注入 system prompt**。

长期记忆召回逻辑在 `LongTermMemory.recall`：

```text
if (queryEmbedding != null && !queryEmbedding.isEmpty()
        && item.getEmbedding() != null && item.getEmbedding().size() == queryEmbedding.size()) {
    sim = cosine(queryEmbedding, item.getEmbedding());
} else {
    buildVocab(query);
    double[] qv = textToVector(query);
    double[] iv = textToVector(item.getContent());
    sim = cosineArr(qv, iv);
}
double s = sim * 0.7 + item.getImportance() * 0.3;
```

也就是说：

- Embedding 可用时，用向量余弦相似度。
- Embedding 不可用时，退化到 TF 词频向量。
- 最终分数是 `0.7 * 相似度 + 0.3 * 重要性`。
- 分数超过阈值后进入候选，排序取 topK。

写入和去重在 `storeClassified`：

```text
if (sim >= consolidationCfg.getDedupThreshold()) {
    if (importance > item.getImportance()) {
        item.setImportance(importance);
    }
    ...
    item.setLastAccessed(LocalDateTime.now());
    return false;
}
```

合并和淘汰在 `consolidate`，分三步：

1. 按时间衰减 importance。
2. 相似度高的去重或合并。
3. TTL 过期且 importance 太低的删除。

相关配置在 `src/main/resources/application.yml`：

```yaml
memory:
  short-term-max-turns: 5
  long-term-top-k: 3
  consolidation:
    similarity-threshold: 0.80
    dedup-threshold: 0.95
    ttl-days: 30
    decay-rate: 0.995
    min-importance: 0.3
    trigger-interval: 5
```

## 三、用户偏好

偏好记忆实现位于：

- `src/main/java/com/agi/assistant/service/memory/PreferenceMemory.java`
- `src/main/java/com/agi/assistant/application/chat/PreferenceFiller.java`
- `src/main/java/com/agi/assistant/service/llm/LlmService.java`
- `src/main/java/com/agi/assistant/application/chat/MemoryWriter.java`

`PreferenceMemory` 本质是一个 KV Map：

```text
private final Map<String, String> data = new ConcurrentHashMap<>();
```

例如：

```text
姓名: 小明
喜好: 篮球
城市: 上海
语言: 中文
```

偏好写入有两条路径。

第一条是在用户消息进入时抽取：

```text
runAsyncPreferenceExtraction(query);
String[] extracted = pref.extractAndSave(query);
```

规则抽取支持：

```text
if (msg.contains("我喜欢")) ...
if (msg.contains("我爱")) ...
if (msg.contains("我叫")) ...
```

LLM 抽取在 `LlmService.extractPreferences`：

```text
String prompt = "从下面这句用户消息中，提取所有用户的个人信息和偏好，输出 JSON 对象...";
String raw = callAPI("", List.of(Map.of("role", "user", "content", prompt)));
```

第二条是在助手回复后，由 `MemoryWriter` 分类提取长期信息：

```text
memoryWriter.writeAfterReply(query, resp.getAnswer());
```

`MemoryWriter` 会让 LLM 从回复中提取：

- `identity`
- `preference`
- `tool_failure`
- `policy`
- `general`

其中 `identity` 和 `preference` 会同时写入 `PreferenceMemory` 和长期记忆：

```text
if ("identity".equals(c.category) || "preference".equals(c.category)) {
    String key = guessPrefKey(c.content);
    String value = c.content;
    if (key != null && !key.isEmpty()) {
        pref.save(key, value);
        infra.savePreference("default", key, value);
    }
}
```

偏好融入 Agent 有两个方式。

### 1. 进入 system prompt

`PreferenceMemory.buildContext` 会生成：

```text
【用户偏好】
姓名: 小明
喜好: 篮球
```

然后 `buildMemorySystemPrefixWithCtx` 会把它拼入 `memPrefix`。

普通对话最终类似：

```text
String sp = ChatHistoryAdapter.buildSystemPrompt(memPrefix,
        "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
resp.setAnswer(llm.chat(sp, histMsgs));
```

### 2. 自动补工具参数

工具调用前会执行：

```text
PreferenceFiller.fill(tc, pref);
```

`PreferenceFiller` 会把偏好映射到工具参数：

```text
private static final Map<String, List<String>> PREF_TO_PARAM = Map.of(
        "城市", List.of("city", "location", "location_name"),
        "时区", List.of("timezone", "tz", "time_zone"),
        "姓名", List.of("name", "username", "user_name"),
        "语言", List.of("language", "lang"),
        "国家", List.of("country", "nation")
);
```

所以偏好不仅影响 LLM 语气，也会实际改变工具调用参数。比如用户偏好里有“城市=上海”，天气工具缺 `city` 时会自动补上。

## 四、图增强记忆

图增强记忆实现位于：

- `src/main/java/com/agi/assistant/service/memory/GraphMemory.java`
- `src/main/java/com/agi/assistant/service/graph/KGStore.java`
- `src/main/java/com/agi/assistant/service/graph/Neo4jStore.java`

初始化在 `UnifiedAgentService.initKnowledgeGraph`：

```text
kg = new KGStore(cfg, (sp, um) -> llm.chat(sp, List.of(Map.of("role", "user", "content", um))));
rag.setKGStore(kg);

graphMem = new GraphMemory(ltm, kg, cfg.getMemory().getConsolidation().getSimilarityThreshold());
graphMem.syncPrevId();
```

Neo4j 不可用时，系统会退化为纯长期记忆：

```text
if (kg.available()) {
    log.info("知识图谱已就绪 (Neo4j)，RAG 升级为三路混合检索，记忆系统接入图层");
} else {
    log.info("Neo4j 不可用，RAG 保持双路检索，记忆系统退化为纯向量模式");
}
```

`GraphMemory` 包在 `LongTermMemory` 外面。写入时先写 LTM，再写 Neo4j：

```text
boolean added = ltm.storeClassified(content, importance, embedding, category, tags, slotHint);
...
kg.upsertMemoryNode(newId, content, importance);
if (prevId >= 0) {
    kg.addMemoryEdge(prevId, newId, "FOLLOWS", 1.0);
}
linkSimilarEdges(newItem, newId);
```

节点类型是：

```text
(:Memory {mem_id, content, importance})
```

自动建立的边主要是两类：

- `FOLLOWS`：上一条记忆指向当前记忆，表达时序。
- `SIMILAR_TO`：embedding 相似度超过阈值的记忆之间建立相似关系。

`KGStore` 允许的记忆边类型包括：

```text
return "FOLLOWS".equals(type) || "SIMILAR_TO".equals(type)
        || "CAUSES".equals(type) || "BELONGS_TO".equals(type);
```

但当前 `GraphMemory` 自动写入逻辑里，实际主要生成的是 `FOLLOWS` 和 `SIMILAR_TO`。`CAUSES / BELONGS_TO` 目前更像是预留能力，还没有形成完整自动抽取链路。

图增强召回在 `GraphMemory.recall`：

```text
List<MemoryItem> seed = ltm.recall(query, topK, queryEmbedding);
if (kg == null || !kg.available() || seed.isEmpty()) {
    return seed;
}
List<Integer> seedIds = new ArrayList<>();
for (MemoryItem it : seed) seedIds.add(it.getId());
List<Integer> expandedIds = kg.expandMemoryNeighbors(seedIds, 1);
```

也就是说：

```text
当前 query
 -> LTM 先召回 seed 记忆
 -> Neo4j 沿 FOLLOWS / SIMILAR_TO / CAUSES / BELONGS_TO 扩展邻居
 -> 把邻居记忆加入结果
 -> 作为【相关记忆】进入 system prompt
```

Neo4j 扩展查询在 `KGStore.expandMemoryNeighbors`：

```text
MATCH (m:Memory) WHERE m.mem_id IN $ids
MATCH (m)-[:FOLLOWS|SIMILAR_TO|CAUSES|BELONGS_TO*1..h]-(n:Memory)
WHERE NOT n.mem_id IN $ids RETURN DISTINCT n.mem_id AS id
```

图层还参与合并保护：

```text
List<Integer> protectedIds = kg.getHighCentralityMemoryIds(result.deleteFromDB, 3);
```

也就是入度较高的图中心节点，会被尝试保护，避免被长期记忆合并/淘汰删掉。

## 五、RAG 图层与记忆图层的区别

这个项目里 Neo4j 同时服务两类东西：

1. RAG 文档知识图谱。
2. 长期记忆图增强。

RAG 文档图在 `RagService.ingest` 中异步建立：

```text
if (kg != null && kg.available()) {
    List<ChunkRef> refs = new ArrayList<>();
    for (Chunk c : chunks) refs.add(new ChunkRef(c.getId(), c.getContent()));
    new Thread(() -> kg.indexDocument(docHash, refs), "kg-index").start();
}
```

RAG 检索在 `HybridStore.searchHybrid` 中融合：

```text
List<InfrastructureService.MilvusHit> milvusHits = infra.milvusSearchWithScores(queryVec, fetchK);
List<InfrastructureService.ESHit> esHits = infra.searchRAGChunks(query, fetchK);

if (kg != null && kg.available()) {
    List<GraphSearchResult> kgHits = kg.search(query, fetchK);
    ...
}
```

所以 RAG 图层是：

```text
Milvus 语义向量 + ES/BM25 关键词 + Neo4j 文档实体关系
 -> RRF 融合
 -> 文档 chunk 进入 RAG 回答
```

记忆图层是：

```text
LongTermMemory 召回 seed + Neo4j Memory 邻居扩展
 -> 相关记忆进入 Agent prompt
```

两者共用 `KGStore / Neo4jStore`，但节点类型和使用路径不同。

## 六、当前实现中的一个重要现状

项目里有一套更完整的 schema-driven 上下文装配器：

- `src/main/java/com/agi/assistant/application/chat/ChatContextBuilder.java`
- `src/main/java/com/agi/assistant/domain/promptctx/Schemas.java`
- `src/main/java/com/agi/assistant/domain/promptctx/source/ProfileSource.java`
- `src/main/java/com/agi/assistant/domain/promptctx/source/RecallSource.java`

它设计上支持：

- `Profile`
- `Planner`
- `TaskMemory`
- `ToolState`
- `Constraints`
- `Recall`

例如 `Schemas.CHAT`：

```text
public static final RuntimeContextSchema CHAT = new RuntimeContextSchema("chat", List.of(
        new Slot(SlotKind.CONSTRAINTS, false, filter(200)),
        new Slot(SlotKind.PROFILE, false, filter(300, 10, "identity", "preference")),
        new Slot(SlotKind.RECALL, false, filterRecall(400, 3, 0.4, "episodic", "fact", "general"))
));
```

但是当前主流程并没有实例化和使用 `ChatContextBuilder`。实际运行主链还是走：

```text
String memPrefix = buildMemorySystemPrefixWithCtx(query);
```

所以目前设计上已经往“上下文槽位化”演进，但运行路径仍以“偏好 + 相关记忆”的简单前缀注入为主。

## 七、整体评价

整体评价：**思路完整，工程骨架不错，但实现还偏 Demo / 原型级，距离一个可靠的 Agent Memory 系统还有明显距离。**

### 优点

1. 读写链路闭环了

用户输入、偏好抽取、长期记忆写入、召回、prompt 注入、回复后再写入，都有代码落点。

2. 降级意识不错

Embedding 不可用退 TF，Neo4j 不可用退 LTM，Postgres 不可用也尽量不阻塞启动。

3. 记忆不只是 append log

有 importance、embedding、category、tags、dedup、merge、decay、TTL，这比很多简单项目强。

4. 偏好对工具调用有实际影响

`PreferenceFiller` 会把城市、时区、语言等偏好补到工具参数里，这比只塞 prompt 更实用。

5. 图层方向对

先 LTM 召回 seed，再用 Neo4j 扩展邻居，是比较自然的图增强记忆方式。

### 主要问题

1. 当前主流程没有用上更完整的 `ChatContextBuilder`

项目写了 schema-driven 的上下文装配器，支持 Profile、Recall、ToolState、Constraints 等槽位，但实际主链还是 `buildMemorySystemPrefixWithCtx()` 这种简单拼接。设计和运行路径有脱节。

2. 长期记忆写入质量不稳

`MemoryWriter` 是从“助手回复”里提取记忆，而不是综合“用户输入 + 助手回复 + 工具结果”。这会漏掉很多用户真实信息，也可能把助手生成的内容误写成事实。

3. 偏好抽取比较粗

规则抽取只支持“我喜欢/我爱/我叫”，LLM 抽取也没有冲突解决、置信度、来源、时间戳版本管理。比如用户后来改口“我现在不喜欢篮球了”，系统只是覆盖或新增，语义上不够稳。

4. 图增强比较浅

README 提到 `FOLLOWS / SIMILAR_TO / CAUSES / BELONGS_TO`，但实际自动建边主要是 `FOLLOWS` 和 `SIMILAR_TO`。`CAUSES / BELONGS_TO` 只是 `KGStore` 支持的合法边类型，还没形成真正的因果/归属抽取链路。

5. 召回排序偏简单

LTM 分数是：

```text
0.7 * similarity + 0.3 * importance
```

图扩展出来的记忆统一给 `0.45`。这能跑，但不够细：缺少 recency、访问频率、关系类型权重、路径长度、用户当前任务类型等因素。

6. 异步写入有一致性风险

多处直接 `new Thread(...)`，没有队列、事务、重试、幂等控制。比如 LTM 先写内存，PG id 后同步，Neo4j 又异步 upsert，中间失败会导致内存、PG、图三者不一致。

7. 合并/删除和图保护有逻辑漏洞

`graphAwareConsolidate()` 先调用 `ltm.consolidate()`，这一步已经把内存里的项删了，然后再从 `deleteFromDB` 里过滤“高中心度保护”的 id。这样可能出现：DB 不删，但内存已经删了。保护逻辑应该在删除前参与决策。

## 八、结论

这个模块适合作为 Agent Memory 的教学型实现或 MVP 骨架，不太适合作为生产级长期记忆系统。

它已经把“记忆如何融入 Agent”这件事跑通了：

- 偏好进入 prompt。
- 偏好补工具参数。
- 长期记忆进入相关回忆。
- 图层扩展长期记忆召回。
- 回复后异步写回长期记忆和偏好。

但真正难的部分，比如记忆可信度、冲突消解、上下文预算、写入幂等、一致性、图关系语义，目前还比较薄。

如果继续迭代，优先级建议是：

1. 把主流程切到 `ChatContextBuilder`，让上下文装配走统一 schema。
2. 把记忆写入改成基于完整 turn 的结构化抽取，即综合用户输入、助手回复、工具结果和模式信息。
3. 重做 consolidation，让图保护、PG、LTM、Neo4j 在一个一致的删除/更新计划里执行。
4. 给偏好增加置信度、来源、更新时间、冲突消解和否定处理。
5. 给图增强补上真正的关系抽取，尤其是 `CAUSES / BELONGS_TO` 的生成和权重设计。


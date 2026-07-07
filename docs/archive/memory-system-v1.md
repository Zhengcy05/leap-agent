# Leap Agent 记忆系统搭建说明

## 背景

这次 `memory-module-v1` 的目标，不是一次性把三层记忆系统全部做完，而是先把最基础、最确定的两层接入主对话链路：

- 短期记忆
- 全局偏好记忆

本阶段明确不包含长期记忆、图记忆、工具参数自动补全，也不追求把记忆做成一个过度抽象的 runtime 平台。目标是先把“记住”和“在 prompt 中生效”这条链路跑通，并且保留后续演进空间。

## 当前边界

当前分支已经落地的能力有：

1. 按 `sessionId` 管理短期记忆窗口
2. 面向整个 `oncallAgent` 管理全局偏好记忆
3. 偏好记忆支持进程重启后的文件恢复
4. 对话时把偏好和短期记忆一起注入系统提示词
5. 提供独立的记忆调试接口用于观测当前状态

当前没有落地的内容有：

1. 长期记忆召回
2. 图增强记忆
3. 工具参数默认值自动补全
4. 权限控制和多租户隔离
5. 更复杂的 memory runtime 组装层

## 模块拆分

记忆域当前位于 `src/main/java/com/leap/agent/domain/memory/`，拆成两个子能力：

- `shortterm`：会话级短期记忆
- `preference`：全局偏好记忆

这次没有把记忆直接堆在 `ChatSessionService` 里，而是把历史窗口收敛成 typed object，把偏好单独抽出成独立领域服务，后续再接长期记忆时可以继续沿用这个边界。

## 短期记忆

### 目标

短期记忆解决的是“当前这轮对话需要知道最近几轮上下文”的问题。它只关心当前 session，不做跨 session 共享，也不做重启恢复。

### 结构

当前结构包括：

- `ShortTermMessage`：单条消息，包含 `role`、`content`、`timestamp`
- `ShortTermMemory`：可变记忆窗口，负责追加和裁剪
- `ShortTermMemorySnapshot`：只读快照，供 prompt 渲染和调试接口使用

### 运行方式

`ChatSessionService` 负责以 `sessionId` 为键管理 `ChatSession`。每个 `ChatSession` 内部持有一个 `ShortTermMemory`，并在构造时固化窗口大小，避免运行期配置变化影响已有 session。

每次用户问答完成后：

1. 追加一条 user 消息
2. 追加一条 assistant 消息
3. 按 `maxWindowSize * 2` 的消息数上限裁剪最老消息

这里按消息粒度裁剪，但由于写入总是成对追加，所以效果上保留的是最近 N 轮完整问答。

### 配置

短期记忆窗口通过下面的配置控制：

```yaml
memory:
  short-term:
    max-window-size: 6
```

## 全局偏好记忆

### 目标

偏好记忆解决的是“用户对这个 Agent 的稳定约定”问题，比如默认回复语言、默认日志地域、默认时间范围。这类信息不应该只留在当前 session，也不应该在服务重启后丢失。

### 作用域

当前偏好是整个 `oncallAgent` 共享的全局配置，不按用户隔离，也不按 session 隔离。

### 受控 key 集

为了避免偏好文件无限发散，这次只允许收敛到固定 key：

- `reply_language`
- `reply_style`
- `cls_region`
- `time_range`
- `service_scope`
- `custom_rules`

这些 key 由 `PreferenceKey` 统一定义，并在输出到 prompt 和调试接口时保持稳定顺序。

### 数据结构

偏好不是简单的 `key -> value`，而是结构化的 `PreferenceEntry`，包含：

- `key`
- `value`
- `source`
- `updatedAt`
- `version`

其中 `source` 目前可能来自：

- `BOOTSTRAP`：启动时从持久化介质回放
- `RULE`：同步规则抽取
- `LLM`：异步 LLM 补充抽取
- `LEGACY`：兼容老格式数据

### 存储方式

偏好持久化通过 `PreferenceRepository` 抽象出来，当前默认实现是 `FilePreferenceRepository`，使用本地 JSON 文件保存：

```yaml
memory:
  preference:
    storage-path: ./data/preferences.json
```

之所以保留仓储抽象，是为了后续切换 PostgreSQL 时不改 chat 主链路。

### 启动恢复

服务启动时，`PreferenceMemoryService` 会从仓储中读取当前 owner 的所有偏好，并回放到进程内缓存中。运行期读取默认都走内存，避免每轮请求都访问文件。

## 偏好提取策略

偏好提取采用“规则同步 + LLM 异步”的双阶段策略。

### 规则同步抽取

入口在收到用户消息之后、构建 prompt 之前。

作用是保证类似下面的话可以从本句开始立即生效：

- 以后默认用中文回答
- CLS 默认查广州
- 默认看近 24 小时

规则抽取主要识别：

- 回复语言
- 回复风格
- 地域
- 时间范围
- 服务范围
- 明显带有“以后 / 默认 / 请始终 / 务必”等信号的自定义规则

同步抽取完成后会立刻写入内存缓存和持久化仓储。

### LLM 异步补充抽取

回复完成后，系统会再基于当前用户输入发起一次低温 LLM 抽取，请模型只输出固定 key 集对应的 JSON 子集。

这里有几个约束：

1. 只读取用户输入，不读取助手回复
2. 没有稳定偏好信号时跳过抽取
3. 解析失败或调用失败只记日志，不影响本轮响应

当前异步执行器是 `Executors.newSingleThreadExecutor(...)`，目的是先保证写入顺序，避免全局偏好被并发覆盖。

## Prompt 注入路径

当前主对话链路里，记忆是通过系统提示词注入的。

`ChatService.buildSystemPrompt(...)` 目前按三段拼接：

1. 基础系统提示
2. `【全局偏好】`
3. `【短期记忆 / 对话历史】`

最后再加一条总约束，要求模型优先遵守全局偏好，并结合短期记忆回答当前问题。

这意味着当前 V1 的记忆读取方式仍然是 prompt 注入，而不是更复杂的 runtime context assembler。这个边界是有意保留的，目的是先把链路跑稳。

## Chat 接口中的接入点

在 `/api/chat` 和 `/api/chat_stream` 两条链路里，当前顺序基本一致：

1. 获取或创建 session
2. 同步规则抽取偏好
3. 读取短期记忆快照
4. 读取全局偏好快照
5. 构建系统提示词
6. 执行对话
7. 追加本轮问答到短期记忆
8. 异步补充抽取偏好

本次没有改动 chat 请求结构，所以外部接口保持兼容。

## 清理语义

`/api/chat/clear` 当前只清指定 session 的短期记忆，不清全局偏好。

这是有意设计出来的区别：

- 短期记忆属于会话上下文
- 偏好记忆属于 Agent 全局约定

如果把两者一起清掉，会让“清 session”和“重置 Agent 偏好”变成一件事，语义会混在一起。

## 调试与观测

本次新增了独立的只读接口：

```http
GET /api/chat/memory
GET /api/chat/memory?sessionId=xxx
```

返回内容包括：

- 全局偏好快照
- 偏好明细视图
- 活动 session 摘要列表
- 指定 session 的短期记忆明细

这里没有复用原来的 `SessionInfoResponse`，而是单独定义了 `MemoryDebugResponse`，避免把记忆观测面和普通 session 查询揉在一起。

## 为什么这版不是玩具实现

虽然这是记忆系统 V1，但当前落地已经刻意避开了纯 demo 式做法，主要体现在：

1. 短期记忆和偏好记忆有明确领域边界
2. 短期记忆使用 typed object，而不是裸 Map
3. 偏好持久化保留 repository abstraction
4. 偏好 key 集是受控的，不允许随意发散
5. 偏好条目有来源、版本和更新时间
6. 启动时支持从持久化介质恢复
7. 调试接口是独立视图，不污染原 session 查询接口

这些点让后续从 V1 继续演进到长期记忆、图记忆、数据库存储时，不需要推倒重来。

## 后续演进落点

基于当前实现，比较自然的下一步包括：

1. 把偏好异步抽取从单线程执行器升级成受控线程池或事件队列
2. 抽出独立的 memory context assembler，避免 `ChatService.buildSystemPrompt(...)` 继续膨胀
3. 引入长期记忆及其召回策略
4. 区分“进入 prompt 的记忆”和“进入工具默认值的记忆”
5. 将 `PreferenceRepository` 切换到 PostgreSQL 或其他持久化后端

这份文档记录的是 V1 的实际落地状态，不代表后续阶段的最终形态。

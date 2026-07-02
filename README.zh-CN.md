# Leap Agent

Leap Agent 是一个基于 Spring Boot 的 AI 助手后端服务，面向知识库问答和 AIOps 排障分析场景。项目集成 DashScope 对话与向量模型、Milvus 向量检索、本地 Agent 工具，以及可选的 MCP 动态工具，通过 REST 和 SSE 接口对外提供能力。

项目当前包含一个轻量级静态测试页面，位于 `src/main/resources/static`。真正的核心能力在后端：文档上传、文档分片、向量化、向量检索、工具增强对话、会话历史管理，以及自动化告警分析报告生成。

## 核心能力

- 工具增强对话：支持普通响应和 SSE 流式响应。
- 会话历史：按会话保留最近的用户/助手消息对。
- 知识库检索：上传的 `txt` 和 `md` 文件会被分片、向量化、写入 Milvus，并可被内部文档工具检索。
- AIOps 工作流：Supervisor Agent 协调 Planner 和 Executor Agent，结合告警、指标、日志和内部文档生成分析报告。
- 工具注册中心：通过统一的运行时注册类组装本地工具和 MCP 动态工具。
- 静态测试页面：Spring Boot 直接托管一个浏览器测试 UI。

## 运行时架构

```text
Client / Static UI
       |
       v
api
  ChatController          REST 和 SSE 接口
  FileUploadController    文档上传入口
  MilvusHealthController  Milvus 健康检查
  SseEventSender          SSE 消息发送封装
       |
       v
domain
  chat                    对话模型创建、ReactAgent 创建、会话状态
  aiops                   Supervisor / Planner / Executor 编排
  rag                     文档分片、向量化、索引、检索、RAG 生成
       |
       v
runtime.tool
  AgentToolRegistry       本地方法工具 + MCP 工具回调
  DateTimeTools           当前时间工具
  InternalDocsTools       基于 Milvus 的内部文档检索
  QueryMetricsTools       Prometheus 告警和指标查询
  QueryLogsTools          CLS 风格日志主题和 Mock 日志查询
       |
       v
infra
  llm                     DashScope 集成配置
  milvus                  Milvus 客户端、集合 schema、索引
```

## 工程结构

```text
Leap Agent/
├── src/main/java/com/leap/agent/
│   ├── LeapAgentApplication.java      # Spring Boot 启动入口
│   ├── api/                           # HTTP API 层
│   │   ├── ChatController.java        # 对话、流式对话、AIOps 接口
│   │   ├── FileUploadController.java  # 知识文档上传
│   │   ├── MilvusHealthController.java# Milvus 健康检查
│   │   └── SseEventSender.java        # SSE 消息发送封装
│   ├── common/                        # 通用配置和 DTO
│   │   ├── config/                    # Web 与属性配置
│   │   └── model/                     # REST DTO、SSE 消息体、文档分片模型
│   ├── domain/                        # 应用领域服务
│   │   ├── aiops/                     # Supervisor / Planner / Executor 工作流
│   │   ├── chat/                      # 对话服务和内存会话
│   │   ├── memory/                    # 预留记忆领域包
│   │   └── rag/                       # 分片、向量化、索引、向量检索
│   ├── infra/                         # 外部基础设施适配
│   │   ├── llm/                       # DashScope 配置
│   │   └── milvus/                    # Milvus 客户端、集合 schema、工具类
│   └── runtime/tool/                  # Agent 工具与工具注册中心
│       ├── AgentToolRegistry.java     # 本地工具 + MCP 工具回调
│       ├── DateTimeTools.java         # 日期时间工具
│       ├── InternalDocsTools.java     # 内部文档检索
│       ├── QueryMetricsTools.java     # Prometheus 告警和指标查询
│       └── QueryLogsTools.java        # CLS 风格日志查询
├── src/main/resources/
│   ├── static/                        # 浏览器测试 UI
│   ├── application.yml                # 本地运行配置
│   └── application-example.yml        # 配置示例
├── aiops-docs/                        # 示例 AIOps 知识文档
├── prometheus/                        # Prometheus 配置和告警规则
└── vector-database.yml                # Milvus standalone compose 文件
```

## 主要请求链路

### 对话

1. `ChatController` 接收 `/api/chat` 或 `/api/chat_stream`。
2. `ChatSessionService` 加载或创建会话，并提供最近历史消息。
3. `ChatService` 创建 DashScope chat model、系统提示词和 ReactAgent。
4. `AgentToolRegistry` 注入本地方法工具和 MCP 工具回调。
5. 结果直接返回，或通过 `SseEventSender` 流式返回。

### 文档上传与检索

1. `FileUploadController` 保存上传的 `txt` 或 `md` 文件。
2. `VectorIndexService` 读取文件，删除同源旧分片，执行文档分片、向量化，并写入 Milvus。
3. 当 Agent 需要内部知识时，`InternalDocsTools` 通过 `VectorSearchService` 检索相关分片。

### AIOps

1. `/api/ai_ops` 启动 AIOps 流程。
2. `AiOpsService` 构建 Planner 和 Executor ReactAgent，并交给 Supervisor Agent 调度。
3. 工具层提供当前时间、内部文档、Prometheus 指标/告警和日志查询能力。
4. 最终 Markdown 报告通过 SSE 流式返回。

## 环境要求

- Java 17
- Maven 3.9+
- Docker，或一个可访问的 Milvus 实例
- DashScope API Key
- 可选：本地 Prometheus，默认地址 `http://localhost:9090`
- 可选：用于日志工具的 MCP SSE endpoint

## 配置

应用读取 `src/main/resources/application.yml`。

关键配置如下：

```yaml
server:
  port: 9900

file:
  upload:
    path: ./uploads
    allowed-extensions: txt,md

milvus:
  host: localhost
  port: 19530
  database: default
  timeout: 10000

spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:your-api-key-here}
    mcp:
      client:
        enabled: true
        type: ASYNC

dashscope:
  api:
    key: ${DASHSCOPE_API_KEY:your-api-key-here}
  embedding:
    model: text-embedding-v4

document:
  chunk:
    max-size: 800
    overlap: 100

rag:
  top-k: 3
  model: qwen3-max

prometheus:
  base-url: http://localhost:9090
  mock-enabled: false

cls:
  mock-enabled: false
```

启动前设置 DashScope API Key：

```bash
export DASHSCOPE_API_KEY=your-api-key
```

## 本地启动

启动 Milvus：

```bash
docker compose -f vector-database.yml up -d
```

启动 Spring Boot 服务：

```bash
mvn spring-boot:run
```

打开静态测试页面：

```text
http://localhost:9900
```

健康检查：

```bash
curl http://localhost:9900/milvus/health
```

## API 参考

### 普通对话

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-1","Question":"如何排查 CPU 使用率过高？"}'
```

响应结构：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "success": true,
    "answer": "...",
    "errorMessage": null
  }
}
```

### 流式对话

```bash
curl -N -X POST http://localhost:9900/api/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-1","Question":"结合内部文档给我一个处理步骤"}'
```

SSE data 消息体：

```json
{"type":"content","data":"..."}
{"type":"error","data":"..."}
{"type":"done","data":null}
```

### 清空会话

```bash
curl -X POST http://localhost:9900/api/chat/clear \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-1"}'
```

### 查询会话信息

```bash
curl http://localhost:9900/api/chat/session/session-1
```

### 上传知识文档

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@aiops-docs/cpu_high_usage.md"
```

支持的文件扩展名由 `file.upload.allowed-extensions` 控制。

### AIOps 报告

```bash
curl -N -X POST http://localhost:9900/api/ai_ops
```

接口会流式返回执行进度和最终 Markdown 报告。

## 内置 Agent 工具

| 工具类 | 用途 |
| --- | --- |
| `DateTimeTools` | 返回当前日期时间。 |
| `InternalDocsTools` | 检索 Milvus 中的内部知识库。 |
| `QueryMetricsTools` | 查询 Prometheus 告警和指标，支持 Mock。 |
| `QueryLogsTools` | 提供日志主题发现和 CLS 风格 Mock 日志查询。 |
| `AgentToolRegistry` | 统一组装本地工具和 MCP 动态工具回调。 |

## 开发说明

- `ChatSessionService` 使用内存保存会话历史，服务重启后会话会丢失。
- `SseEventSender` 是 SSE 事件格式化和发送的唯一入口。
- `AgentToolRegistry` 是增删 Agent 工具的统一入口。
- `application-example.yml` 可作为配置模板。
- `aiops-docs/` 提供可上传到 Milvus 的示例运维知识文档。

## 验证

编译项目：

```bash
mvn -DskipTests compile
```

如果本地服务已经启动，可以做一个简单 smoke check：

```bash
curl http://localhost:9900/milvus/health
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"smoke","Question":"现在是什么时间？"}'
```

## 项目信息

- 作者：Zhengcy05 <1825478405@qq.com>
- 当前版本：v1.0.0

## 版本迭代记录

| 版本 | 变更说明 |
| --- | --- |
| v1.0.0 | 确立当前 Spring Boot Agent 工程结构，包含对话、SSE 流式输出、RAG 文档索引、AIOps 工作流、统一工具注册中心、英文 README 和中文 README。 |

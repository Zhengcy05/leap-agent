# Leap Agent

Leap Agent is a Spring Boot based AI assistant service for knowledge-base Q&A and AIOps investigation workflows. It combines DashScope chat and embedding models, Milvus vector search, local agent tools, and optional MCP tools into one backend service with REST and SSE APIs.

The project currently ships with a lightweight static web UI under `src/main/resources/static`, but the core value is the backend: document ingestion, vector retrieval, tool-augmented chat, session history, and automated alert-analysis report generation.

## Capabilities

- Chat with tool calling: regular response and SSE streaming response are both supported.
- Session history: recent user/assistant message pairs are retained in memory per session.
- Knowledge retrieval: uploaded `txt` and `md` files are chunked, embedded, stored in Milvus, and searched by internal document tools.
- AIOps workflow: a Supervisor Agent coordinates Planner and Executor agents to inspect alerts, metrics, logs, and internal runbooks.
- Tool registry: local tools and MCP-provided dynamic tools are assembled through a central runtime registry.
- Static test UI: the service exposes a browser UI from Spring Boot static resources.

## Runtime Architecture

```text
Client / Static UI
       |
       v
api
  ChatController          REST and SSE endpoints
  FileUploadController    document upload entrypoint
  MilvusHealthController  Milvus health probe
  SseEventSender          shared SSE message sender
       |
       v
domain
  chat                    chat model creation, ReactAgent creation, session state
  aiops                   Supervisor / Planner / Executor orchestration
  rag                     chunking, embedding, indexing, vector search, RAG answer flow
       |
       v
runtime.tool
  AgentToolRegistry       local method tools + MCP callbacks
  DateTimeTools           current time tool
  InternalDocsTools       Milvus-backed document lookup
  QueryMetricsTools       Prometheus alert and metric lookup
  QueryLogsTools          CLS mock log lookup / local log tool
       |
       v
infra
  llm                     DashScope integration configuration
  milvus                  Milvus client, schema, collection, indexes
```

## Source Layout

```text
Leap Agent/
├── src/main/java/com/leap/agent/
│   ├── LeapAgentApplication.java      # Spring Boot entrypoint
│   ├── api/                           # HTTP API layer
│   │   ├── ChatController.java        # Chat, streaming chat, AIOps endpoints
│   │   ├── FileUploadController.java  # Knowledge document upload
│   │   ├── MilvusHealthController.java# Milvus health probe
│   │   └── SseEventSender.java        # Shared SSE sender
│   ├── common/                        # Shared configuration and DTOs
│   │   ├── config/                    # Web and property configuration
│   │   └── model/                     # REST DTOs, SSE payloads, document chunks
│   ├── domain/                        # Application domain services
│   │   ├── aiops/                     # Supervisor / Planner / Executor workflow
│   │   ├── chat/                      # Chat service and in-memory sessions
│   │   ├── memory/                    # Reserved memory domain package
│   │   └── rag/                       # Chunking, embedding, indexing, vector search
│   ├── infra/                         # External infrastructure adapters
│   │   ├── llm/                       # DashScope configuration
│   │   └── milvus/                    # Milvus client, schema, collection utilities
│   └── runtime/tool/                  # Agent tools and tool registry
│       ├── AgentToolRegistry.java     # Local tools + MCP callbacks
│       ├── DateTimeTools.java         # Date/time tool
│       ├── InternalDocsTools.java     # Internal document retrieval
│       ├── QueryMetricsTools.java     # Prometheus alert and metric lookup
│       └── QueryLogsTools.java        # CLS-style log lookup
├── src/main/resources/
│   ├── static/                        # Browser test UI
│   ├── application.yml                # Local runtime configuration
│   └── application-example.yml        # Example configuration
├── docs/
│   ├── aiops/                         # Sample AIOps knowledge documents
│   └── generated/                     # Generated analysis and reference docs
├── prometheus/                        # Prometheus config and alert rules
└── vector-database.yml                # Milvus standalone compose file
```

## Main Request Flow

### Chat

1. `ChatController` receives `/api/chat` or `/api/chat_stream`.
2. `ChatSessionService` loads or creates the session and provides recent history.
3. `ChatService` builds the DashScope chat model, prompt, and ReactAgent.
4. `AgentToolRegistry` injects local method tools and MCP callbacks.
5. The response is returned directly or streamed through `SseEventSender`.

### Document Upload And Retrieval

1. `FileUploadController` saves an uploaded `txt` or `md` file.
2. `VectorIndexService` reads the file, deletes older chunks for the same source, chunks the document, embeds each chunk, and inserts vectors into Milvus.
3. `InternalDocsTools` uses `VectorSearchService` to retrieve relevant chunks when an agent needs internal knowledge.

### AIOps

1. `/api/ai_ops` starts the AIOps flow.
2. `AiOpsService` builds Planner and Executor ReactAgents and places them under a Supervisor Agent.
3. Tools provide access to time, internal documents, Prometheus, and logs.
4. The final Markdown report is streamed to the client.

## Requirements

- Java 17
- Maven 3.9+
- Docker or another reachable Milvus instance
- DashScope API key
- Optional: Prometheus at `http://localhost:9090`
- Optional: MCP SSE endpoint for Tencent CLS style log tools

## Configuration

The application reads configuration from `src/main/resources/application.yml`.

Important keys:

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

Set the API key before starting the service:

```bash
export DASHSCOPE_API_KEY=your-api-key
```

## Local Startup

Start Milvus:

```bash
docker compose -f vector-database.yml up -d
```

Start the Spring Boot service:

```bash
mvn spring-boot:run
```

Open the static UI:

```text
http://localhost:9900
```

Health check:

```bash
curl http://localhost:9900/milvus/health
```

## API Reference

### Chat

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-1","Question":"如何排查 CPU 使用率过高？"}'
```

Response shape:

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

### Streaming Chat

```bash
curl -N -X POST http://localhost:9900/api/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-1","Question":"结合内部文档给我一个处理步骤"}'
```

SSE data payloads use this shape:

```jsonl
{"type":"content","data":"..."}
{"type":"error","data":"..."}
{"type":"done","data":null}
```

### Clear Session

```bash
curl -X POST http://localhost:9900/api/chat/clear \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-1"}'
```

### Session Info

```bash
curl http://localhost:9900/api/chat/session/session-1
```

### Upload Knowledge Document

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@docs/aiops/cpu_high_usage.md"
```

Supported extensions are controlled by `file.upload.allowed-extensions`.

### AIOps Report

```bash
curl -N -X POST http://localhost:9900/api/ai_ops
```

The endpoint streams progress and the final Markdown report.

## Built-In Agent Tools

| Tool class | Purpose |
| --- | --- |
| `DateTimeTools` | Returns current date and time. |
| `InternalDocsTools` | Searches the Milvus-backed internal knowledge base. |
| `QueryMetricsTools` | Queries Prometheus alerts and metric data, with mock support. |
| `QueryLogsTools` | Provides log topic discovery and mock CLS-style log queries. |
| `AgentToolRegistry` | Assembles local tools and MCP dynamic callbacks for agents. |

## Development Notes

- `ChatSessionService` stores session history in memory. Restarting the service clears sessions.
- `SseEventSender` is the only place that formats SSE events.
- `AgentToolRegistry` is the single place to add or remove tools exposed to agents.
- `application-example.yml` mirrors the local configuration shape and can be used as a starting point.
- `docs/aiops/` contains sample operational documents that can be uploaded into Milvus for internal-document retrieval.
- `docs/generated/` contains generated analysis and reference documents, such as memory-system design notes for future integration.

## Verification

Compile the project:

```bash
mvn -DskipTests compile
```

If the service is running locally, a quick smoke check is:

```bash
curl http://localhost:9900/milvus/health
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"smoke","Question":"现在是什么时间？"}'
```

## Project Info

- Author: Zhengcy05 <1825478405@qq.com>
- Current version: v1.0.0

## Version History

| Version | Changes |
| --- | --- |
| v1.0.0 | Established the current Spring Boot Agent architecture, including chat, SSE streaming, RAG document indexing, AIOps workflow, centralized tool registry, English README, and Chinese README. |

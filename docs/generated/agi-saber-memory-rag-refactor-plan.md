# AGI-saber Memory/RAG Refactor Plan for Leap Agent

## Target Architecture

Leap Agent should follow the AGI-saber split of responsibilities:

1. PostgreSQL is the source of truth for durable facts.
2. Milvus is a rebuildable vector index.
3. Elasticsearch is a rebuildable keyword/BM25 projection index.
4. In-memory services are request/runtime caches and consolidation workers.

This means neither RAG chunks nor long-term memories should rely on Milvus as the only durable copy of content.

## Current Leap Agent State

1. RAG writes chunk facts into PostgreSQL, then writes projections into Milvus and Elasticsearch.
2. RAG search merges Milvus semantic candidates and Elasticsearch keyword candidates, then hydrates active chunks from PostgreSQL.
3. Long-term memory uses PostgreSQL as the recommended fact source, with file storage as fallback.

## Refactor Rules

1. RAG ingestion writes chunks to PostgreSQL first, then indexes vectors into Milvus.
2. Milvus records use the PostgreSQL chunk id as their primary id.
3. RAG search gets candidate ids from Milvus and Elasticsearch, then hydrates active chunks from PostgreSQL.
4. PostgreSQL rows carry `version`, `content_hash`, `status`, and timestamps.
5. Deletes should tombstone PostgreSQL rows and then best-effort delete Milvus vectors and Elasticsearch docs.
6. If Milvus or Elasticsearch is stale, PostgreSQL status wins during hydration.
7. Elasticsearch indexes from the same PostgreSQL facts, not from Milvus.

## Scope of This Pass

Implemented in this pass:

1. PostgreSQL-backed RAG chunk repository.
2. Local schema initialization for RAG chunk table.
3. RAG indexing flow changed to `PG upsert -> Milvus vector index`.
4. Elasticsearch keyword projection index.
5. RAG search changed to `Milvus + ES candidate ids -> PG hydrate`.
6. Compose/config/docs updated for local PostgreSQL and Elasticsearch.
7. Long-term memory extraction is LLM-only and narrowed away from preference slots, user profile rules, and toward facts, troubleshooting cases, and tool lessons.
8. Neo4j memory graph projection for long-term memories: Memory nodes, FOLLOWS/SIMILAR_TO edges, graph-expanded recall, and centrality-aware TTL protection.

Deferred:

1. Durable outbox table and background index worker.
2. pgvector replacement for Milvus.
3. Cross-service distributed transaction guarantees.
4. LLM extraction for CAUSES/BELONGS_TO graph edges.

## Consistency Model

The intended consistency model is PG-strong and index-eventual:

1. PostgreSQL is authoritative.
2. Milvus/Elasticsearch are projections.
3. Queries always hydrate from PostgreSQL before prompt injection.
4. Stale index hits are filtered out by PostgreSQL status/version.
5. Index rebuild can be done by scanning PostgreSQL.
6. Neo4j is also a projection and can be rebuilt by scanning PostgreSQL long-term memories.

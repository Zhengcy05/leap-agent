package com.leap.agent.domain.rag;

import java.util.List;

/**
 * RAG chunk 事实源仓储。
 */
public interface RagDocumentChunkRepository {

    void markSourceDeleted(String source);

    RagDocumentChunk save(RagDocumentChunk chunk);

    List<RagDocumentChunk> findActiveByIds(List<String> ids);
}

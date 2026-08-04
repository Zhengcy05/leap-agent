package com.leap.agent.domain.rag;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PostgreSQL 中的 RAG 文档分片事实记录。
 */
@Getter
@Setter
public class RagDocumentChunk {

    /** 分片唯一ID */
    private String id;

    /** 来源标识 */
    private String source;

    /** 原始文件名称 */
    private String fileName;

    /** 文件后缀扩展名 */
    private String extension;

    /** 当前分片序号 */
    private int chunkIndex;

    /** 整体文档总分片数量 */
    private int totalChunks;

    /** 文档/分片标题 */
    private String title;

    /** 分片文本内容 */
    private String content;

    /** 自定义扩展元数据 */
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /** content内容哈希，用于去重校验 */
    private String contentHash;

    /** 版本号，用于乐观锁更新 */
    private long version;

    /** 状态：ACTIVE正常、DELETED已删除、DISABLE禁用 */
    private String status = "ACTIVE";

    /** 创建时间戳(毫秒) */
    private long createdAt;

    /** 更新时间戳(毫秒) */
    private long updatedAt;
}

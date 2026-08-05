/**
 * 长期记忆域。
 *
 * <p>V2 以 PostgreSQL 作为长期记忆事实源；Neo4j 仅保存可重建的 Memory 节点和
 * FOLLOWS / SIMILAR_TO 图投影。召回先按 embedding/TF 产生 seed，再用图扩展关联记忆，
 * 最后仍以已加载的 active 长期记忆条目为准注入 system prompt。</p>
 */
package com.leap.agent.domain.memory.longterm;

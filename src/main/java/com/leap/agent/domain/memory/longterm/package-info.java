/**
 * 长期记忆域。
 *
 * <p>V2 先落地轻量闭环：对话后异步抽取稳定事实/偏好/约束，写入本地持久化仓储；
 * 下一轮请求前按当前问题召回相关条目，再注入 system prompt。图增强记忆暂不接入，
 * 避免在当前阶段引入 Neo4j/图一致性治理成本。</p>
 */
package com.leap.agent.domain.memory.longterm;

/**
 * 核心能力三：Memory 三层记忆。
 *
 * <p>{@code MemoryService} 统一门面，内部委托 {@code SessionManager}（会话记忆）和
 * {@code LongTermMemoryStore}（长期记忆，markdown / postgres / mem0 三档），
 * 以及内置工具 {@code save_memory}、{@code recall_memory}，见技术方案第 5 章。
 */
package com.agentos.memory;

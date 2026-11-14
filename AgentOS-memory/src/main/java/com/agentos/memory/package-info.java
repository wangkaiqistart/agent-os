/**
 * 核心能力三：Memory 三层记忆。
 *
 * <p>{@code MemoryService} 统一门面，内部委托 {@code SessionManager}（会话记忆）和
 * {@code LongTermMemoryStore}（长期记忆，markdown / postgres / mem0 三档），
 * 以及内置工具 {@code save_memory}、{@code recall_memory}，见技术方案第 5 章。
 *
 * <p>当前目录只有模块说明，真正的记忆实现还没有加入；这里描述的是设计边界，不代表这些类已经存在。
 */
package com.agentos.memory;

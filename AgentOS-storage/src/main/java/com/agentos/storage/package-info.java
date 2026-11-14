/**
 * 持久化层：PostgreSQL 数据源与 Flyway 迁移脚本，会话、轮次、消息、审计（tool_invocations、llm_calls）、
 * 定时任务等仓库实现，以及 ShedLock 的 JDBC {@code LockProvider}。
 *
 * <p>表字段以技术方案 9.2 为准；{@code spring.jpa.open-in-view=false}，事务不包住 LLM 调用和工具调用。
 *
 * <p>当前目录只有模块说明；数据库实体、Repository 和 Flyway 脚本仍属于后续实现内容。
 */
package com.agentos.storage;

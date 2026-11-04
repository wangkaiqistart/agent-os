/**
 * 核心能力一：对接 LLM。
 *
 * <p>{@code ProviderService} 按 {@code agentos.providers.<name>} 编程式构建 {@code ChatModel}，
 * 以 provider name 为键显式映射；按 {@code agentos.embedding.*} 构建全局唯一的 {@code EmbeddingModel}。
 * 重试由本模块控制，见技术方案第 3 章。
 */
package com.agentos.provider;

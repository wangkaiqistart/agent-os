/**
 * 核心抽象与引擎。
 *
 * <p>包含 {@code TurnContext}、{@code TurnSink}、{@code ReActLoop}、{@code PromptBuilder}、
 * {@code ToolExecutor}（封装注入的 {@code ToolCallingManager}）、{@code AgentService}、{@code AgentScheduler}
 * 等。契约放在本模块，实现放在具体模块（依赖倒置），见技术方案第 4 章与第 10 章。
 *
 * <p>阅读本模块时先看接口和 record：它们描述“系统允许做什么”和“数据长什么样”；
 * 不要期待这里直接连接数据库或调用某家模型，那些属于其他模块的实现细节。
 */
package com.agentos.core;

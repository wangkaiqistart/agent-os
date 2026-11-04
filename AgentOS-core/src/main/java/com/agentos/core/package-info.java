/**
 * 核心抽象与引擎。
 *
 * <p>包含 {@code TurnContext}、{@code TurnSink}、{@code ReActLoop}、{@code PromptBuilder}、
 * {@code ToolExecutor}（封装注入的 {@code ToolCallingManager}）、{@code AgentService}、{@code AgentScheduler}
 * 等。契约放在本模块，实现放在具体模块（依赖倒置），见技术方案第 4 章与第 10 章。
 */
package com.agentos.core;

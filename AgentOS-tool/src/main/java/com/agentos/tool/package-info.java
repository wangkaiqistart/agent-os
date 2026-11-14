/**
 * 核心能力四：Tool 体系（三合一模块，不要拆）。
 *
 * <p>内置 Tool（文件、Shell、HTTP、通知）、{@code GovernedToolCallback}（审计、超时、幂等重试、截断、脱敏）、
 * {@code ToolRegistry}（重名校验、按 Profile 过滤、工具搜索）、{@code McpClientService}、
 * {@code Sandbox} 与 {@code WhitelistSandbox}、{@code NotifyChannelAdapter}，见技术方案第 6 章。
 *
 * <p>工具模块的关键原则是“只有一个执行入口”：工具最终都应经过统一治理层，不能在不同入口各自执行一套。
 */
package com.agentos.tool;

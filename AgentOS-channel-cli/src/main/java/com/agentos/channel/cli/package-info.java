/**
 * CLI Channel：{@code CliChannel} 读 stdin 写 stdout，每条输入调用 {@code AgentService.process}，
 * 支持 {@code /quit} 退出，见技术方案 8.4。
 *
 * <p>这里的 Channel 是“消息入口适配器”，它不应该自己实现 Agent 推理；
 * CLI、REST 和定时任务最终应汇入同一个 AgentService。
 */
package com.agentos.channel.cli;

/**
 * CLI Channel：{@code CliChannel} 读 stdin 写 stdout，每条输入调用 {@code AgentService.process}，
 * 支持 {@code /quit} 退出，见技术方案 8.4。
 */
package com.agentos.channel.cli;

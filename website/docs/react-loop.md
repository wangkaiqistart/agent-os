---
title: ReAct 与轮次
lang: zh-CN
description: AgentOS 已实现的 ReAct、工具执行、会话历史、租约与审计，以及尚未实现的扩展边界。
---

# ReAct 与轮次 {#react-loop}

AgentOS 的 `ReActLoop` 自己驱动 Reason → Act → Observe，组件使用 Spring AI 2.0。US-2 已实现 CLI、HTTP GET、会话历史、租约、超时和 PostgreSQL 审计；长期记忆、MCP、REST、调度与 SSE 属于后续能力。验收使用独立 PostgreSQL；真实模型/真实天气的结果见下方验收说明。

## 一次轮次 {#one-turn}

1. 校验 Agent、Provider 和工具名单，原子取得会话租约、创建轮次并写用户消息；
2. 组装 Agent 正文、Bootstrap、安全说明、日期、完整轮次历史和当前消息；
3. 经 Provider 直接调用 `ChatModel.call` 或 `stream`，逐次写模型审计；
4. 没有工具调用时保存最终回答；有工具调用时由唯一 `ToolExecutor` 调用注入的 `ToolCallingManager`；
5. 工具先写 `in_progress` 再执行，更新终态，将助手工具请求和工具结果追加到消息表，再进入下一次迭代；
6. 保存轮次终态、收尾遗留工具并释放自己的租约。

模型只决定是否调用工具。工具按响应顺序执行，按调用 ID 回填，不自动重试。

## 两道上限 {#limits}

| 上限 | 行为 |
|---|---|
| `settings.max_iterations`，默认10 | 达到上限以 `MAX_ITERATIONS` 结束，不额外请求模型总结 |
| `spring.ai.tools.limits.*` | 跨迭代累计；超限工具不执行，反馈错误并写 `limit_skipped` |

## 超时 {#timeouts}

单次模型、单个工具和整轮分别有超时；每次操作都取自身时限与整轮剩余时间的较小值。整轮默认300秒，到期停止新外呼，在途最终审计、终态和租约收尾共用最后1秒。数据库操作和输出回调也扣同一预算。

HTTP 同步等待后返回202属于后续 Web Service，当前没有这一入口。Shell/MCP 的 I/O 超时属于后续工具实现。

## 会话租约 {#lease}

同一会话同时只运行一轮；CLI 后到的消息显示“上一条消息还在处理”，不产生写入。每次迭代和工具调用前按 owner 续租，租约至少覆盖整轮截止时间再加30秒。数据库在取锁后用实际时间判断有效期。

丢租约后立即停止；启动与过期接管会回收失效 RUNNING 和孤儿工具记录，不清理有效或新 owner 的租约。强杀后可能需要等待原租约过期，当前没有周期扫描或后台心跳。

## 历史与上下文 {#history}

历史按完整轮次同时满足20轮、32000估算 token 的默认上限；token 使用90%预算留余量，任一上限0表示不带历史。失败历史只携带用户消息和安全说明，数据库原记录保留。每轮重读 Agent 与 Bootstrap。

`chat --new` 在空闲时归档旧会话、开始新会话；旧轮次、消息和审计保持不变，旧历史不进入新提示词。长期记忆和 Skill 目前仅预留位置。

## 流式调用 {#streaming}

`TurnSink` 的文本增量、工具请求/结果、完成与出错事件已有测试覆盖；Provider 用 `MessageAggregator` 聚合。CLI 当前使用同步入口，没有正式 SSE API。终结回调至多一次，失败不改已确认结果。

## 审计 {#audit}

`llm_calls` 记录每次模型尝试，`tool_invocations` 先记后做并更新同一行。审计瞬时写失败可有限重试，稳定键防重复；提交未知先查证，不能重发模型或工具。写入前脱敏，日志不输出完整提示词或工具参数/结果。

`CONFIRMED` 表示必要审计、终态及本 owner 租约清理已确认，不代表业务一定成功。持续数据库故障时停止并返回 turnId 与 `UNCONFIRMED`，不承诺自动补齐缺失模型审计。

## 验收说明 {#validation}

2026-11-17 已验收：真实 primary 加本地天气替身通过（2次模型、1次工具、4条消息）；真实 primary 加 met.no 的两次独立 CLI 进程通过，第二次使用 `--new`（合计4次模型、2次工具、8条消息，旧记录零改动）。这些是本次样本，模型以后可能多次取数；精确固定次数由脚本模型验证。具体通过/未运行状态与调用计数以[仓库验收记录](https://github.com/wangkaiqistart/agent-os/blob/feat/us-2-react/specs/002-react-loop/quickstart.md)为准；没有记录的真实 Demo 不算通过。

## 设计依据 {#sources}

- [技术方案（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [功能规格与契约](https://github.com/wangkaiqistart/agent-os/tree/feat/us-2-react/specs/002-react-loop)

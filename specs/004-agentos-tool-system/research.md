# Research: AgentOS 工具注册与安全执行

**Feature**: [spec.md](./spec.md)
**Date**: 2026-11-18

## R1. 工具执行边界

**Decision**: 所有模型工具调用进入 `ToolExecutor`，再委托容器注入的 Spring AI `ToolCallingManager.executeToolCalls`；每个已授权回调外层包裹 `GovernedToolCallback`。

**Rationale**:

- 宪章把唯一执行入口列为不可协商原则，避免循环、工具目录和模型适配器各自执行造成重复调用或绕过审计。
- Spring AI 已定义 `ToolCallback`、工具定义、参数传递和调用结果历史；重复创建平行协议会破坏官方 Manager 的限次、错误处理和观测行为。
- `ToolContext` 能显式传递轮次、租约、预算和调用账本，不依赖 `ThreadLocal`，也能跨虚拟线程保持业务上下文。

**Alternatives considered**:

- 在 `ReActLoop` 中直接遍历回调：会绕过官方 Manager 和统一限次，拒绝。
- 使用 `ToolCallingAdvisor` 或 `ChatClient` 自动执行：失去循环对迭代、流式事件和整轮截止时间的控制，拒绝。
- 每种工具来源定义独立执行接口：增加重复协议和安全分支，拒绝。

**Evidence**: `AgentOS-core/.../ToolExecutor.java`、`ReActLoop.java`、`AgentOS-tool/.../ToolAutoConfiguration.java`、constitution IV。

## R2. 默认限次与错误策略

**Decision**: 默认模型调用 10 次、单工具 10 次、本轮工具总数 30 次；达到工具上限时返回安全工具错误，允许模型在本轮收口；达到模型迭代上限时停止继续外呼并返回上限说明。只有 `idempotent=true` 的工具可对明确瞬时失败自动重试，默认最多 1 次重试；每次尝试都计入工具预算。

**Rationale**:

- 规格澄清已确定这些默认值。
- 当前 `application.yaml` 将 Spring AI 工具限次设置为 10/30/`RETURN_ERROR_RESPONSE`，Agent 设置提供独立的最大迭代上限。
- 两套计数解决不同风险：模型调用次数限制循环深度，工具次数限制单次响应和跨迭代的动作数量。

**Alternatives considered**:

- 使用 Spring AI 默认的更宽上限：资源和副作用边界不可预测，拒绝。
- 超限直接抛异常结束：无法让模型利用安全错误收尾，且与规格确认的继续本轮语义不符。
- 只限制模型迭代不限制工具调用：单次响应多工具仍可耗尽资源，拒绝。

**Evidence**: `AgentOS-boot/src/main/resources/application.yaml`、`AgentOS-core/.../AgentSettings.java`、`ReActLoopLimitsTest`、`ToolCallLimitsTest`。

## R3. HTTP 白名单与安全解析

**Decision**: 首期只提供只读 HTTP GET；每一跳执行默认拒绝的域名白名单校验，允许绝对 HTTP/HTTPS URL，拒绝 user-info，通配符只允许子域，IP 必须精确匹配；禁止自动跳转，由工具控制最多 3 次跳转。

**Rationale**:

- 工具输入由模型生成，网络访问必须在真实请求前授权。
- 白名单按解析后的 host 比较，避免把路径、查询参数或大小写差异当作授权依据。
- 每次跳转重新授权，防止白名单域名把请求引向未经批准的目标。
- 当前 `HttpTools` 使用 JDK HttpClient 和有界 BodySubscriber，能在正文读取过程中检查大小和共享 deadline。

**Alternatives considered**:

- 允许任意 URL：无法满足最小权限和 SSRF 风险边界，拒绝。
- 依赖 HttpClient 自动跳转：无法逐跳检查目标，拒绝。
- 超限后返回已读正文片段：可能回显敏感响应或导致大结果进入模型，按澄清结果拒绝。
- 解析 DNS 后自动允许内网地址：不是完整网络隔离，且会把环境依赖混入工具授权，拒绝。

**Evidence**: `AgentOS-tool/.../WhitelistSandbox.java`、`HttpTools.java`、`HttpToolsTest`、`HttpToolsDeadlineTest`、`application.yaml`。

## R4. 审计存储与故障语义

**Decision**: `tool_invocations` 以 `(turn_id, iteration, tool_call_id)` 作为稳定唯一键；执行前独立短事务写入 `in_progress`，执行后独立短事务更新原行。瞬时数据库故障按有限重试处理；完成写入未确认时把原行标为 `unconfirmed` 并禁止重跑外部动作。执行前审计无法确认时不启动底层工具，也不伪造工具尝试行。

**Rationale**:

- 先记后做是工具安全边界；同一调用不能以新增记录方式重跑。
- 独立事务避免网络或工具 I/O 持有数据库连接和长事务。
- 唯一键和相同载荷查证可以处理提交响应丢失，避免重复审计或重复副作用。
- PostgreSQL/Flyway 是宪章指定的唯一关系存储，审计必须在核心阶段落库。

**Alternatives considered**:

- 只写日志：无法在会话恢复和数据库查询中追溯，违反 day-one 审计原则。
- 把模型/工具调用包进一个长事务：会持有连接并放大锁等待，违反事务边界约束。
- 完成写入失败后自动重跑工具：可能重复外部动作，拒绝。
- 用内存 map 作为生产审计：进程重启即丢失，拒绝。

**Evidence**: `AgentOS-storage/.../JpaToolInvocationRecorder.java`、`ToolInvocationEntity.java`、`V1__core_tables.sql`、`AuditTransactionTest`。

## R5. 预算、取消和结果安全

**Decision**: 模型调用、工具执行、HTTP 连接/响应头/正文、审计收尾共享 `TurnExecutionBudget`；工具等待取单工具策略超时与剩余整轮预算的较小值；硬收尾窗口只用于清理，不延长用户可见结果；所有错误和结果经过 Redactor 与长度限制，普通工具结果默认最多 10000 个字符；取消、完成状态未知或审计未确认时禁止重试。

**Rationale**:

- 共享单调时钟截止时间能防止持续响应分片或多次跳转把等待时间无限延长。
- 工具自身超时与整轮超时必须区分，才能决定是否继续模型循环。
- 取消 Future、HTTP body subscription 和虚拟线程执行器能在超时后停止后续动作。
- 脱敏和长度限制必须在工具执行层完成，不能依赖日志格式或上层 UI 补救。

**Alternatives considered**:

- 每次跳转/每次迭代重新开始一段固定超时：会让总等待超出用户预算，拒绝。
- 只配置 HttpRequest timeout：无法约束响应正文消费和审计收尾，拒绝。
- 仅在日志输出时脱敏：工具结果仍会进入模型和数据库，拒绝。

**Evidence**: `TurnExecutionBudget.java`、`GovernedToolCallback.java`、`HttpTools.java`、`Redactor.java`。

## R6. 范围和扩展方式

**Decision**: 先交付内置 HTTP GET 与内部统一 `ToolCallback` 注册契约；远程工具协议客户端、复杂策略、并行执行、容器隔离和工具搜索延期。

**Rationale**:

- 宪章将 Tool 模块限定为单一实现模块，同时明确扩展三档最终归一为 `ToolCallback`。
- 当前仓库已存在安全边界和 PostgreSQL 审计，继续扩大工具来源会引入额外连接、重试和故障模型。
- 统一契约允许后续增加工具而不改循环控制逻辑。

**Alternatives considered**:

- 首期同时接入所有文件、Shell、通知和远程工具：扩大副作用面，无法用同一验收窗口证明安全。
- 先实现复杂策略再交付基本闭环：推迟可演示的最小工具链，违反按 user story 演示的原则。
- 将 Agent/Skill 目录注册成工具：混淆 Agent 配置与动作能力，违反宪章 VIII。

# Feature Specification: 工具交互式授权

**Feature Branch**: `specs/005-expand-tool-capabilities`（在 005 分支上继续交付）

**Created**: 2026-11-19

**Status**: Implemented

**Input**: "工作区内的文件都要支持读；写需要用户确认；shell、http 任何程序和网址都可以执行，但需要授权；都通过 ask 的方式；参考 Claude Code 的方式实现 CLI 形式的 ask。"

## 背景

005 的工具授权是"静态白名单，未列出即拒绝"。读写目录、命令、域名默认都为空，Agent 声明了工具也几乎做不了事，Skill 正文也读不了。本功能把白名单改为"预先授权"，未列出但合法的动作在交互入口询问用户。

## 已确认的决策

- 工作区（`agentos.workspace.root`）内文件直接可读；工作区外读写一律拒绝，除非部署者在配置里列出。
- 等待用户授权期间**暂停整轮计时**和该工具自身计时。
- "本会话不再询问"按会话（sessionId）生效，只存在于进程内存，不写回配置；后续 HTTP 入口复用同一存储。
- 无人值守入口（`chat --message`、REST 调试、未来的定时任务）不询问，直接拒绝。

## User Scenarios

### US1 - 在 CLI 中确认有副作用的操作（P1）

用户在 `AgentOS chat` 中让 Agent 写文件、执行命令或访问网址。执行前终端显示动作摘要和编号选项，用户选择后才执行。

**验收场景**：
1. **Given** 工作区内的目标文件，**When** 调用 write_file 或 edit_file，**Then** 显示文件、方式（新建/覆盖/编辑）与大小，用户选 1 后写入成功。
2. **Given** 未预授权的程序（如 `git`），**When** 调用 shell，**Then** 显示真实程序路径、参数、目录；用户允许后执行。
3. **Given** 未列入白名单的域名，**When** 调用 http_get 或 http_post，**Then** 显示方法与地址（查询串中的密钥类参数已遮蔽），用户允许后才发出请求；http_get 跳转到新域名时再次询问。
4. **Given** 用户选择"本会话不再询问"，**When** 同一会话再次执行同类动作，**Then** 不再询问；换会话后重新询问。
5. **Given** 用户拒绝并输入原因，**When** 工具返回，**Then** 动作未执行，模型收到"用户拒绝"及原因。

### US2 - 无人值守入口零动作（P1）

**验收场景**：无交互能力的入口遇到需授权的动作时立即拒绝，真实动作数为 0，模型收到"无人值守、可由部署者预先授权"的说明。配置白名单内的动作照常执行。

### US3 - 人工等待不耗尽整轮预算（P1）

**验收场景**：`turn_timeout: 2s` 的 Agent，用户 3 秒后才允许，该轮仍成功完成；单次等待超过 `agentos.tools.approval.timeout`（默认 10 分钟）按拒绝处理。

## Functional Requirements

- **FR-001**：工作区内文件读取无需确认；工作区外只能访问 `read-allowed-paths` / `write-allowed-paths`；锁目录与临时文件始终拒绝。
- **FR-002**：工作区内写入需用户确认；配置的写根视为预先授权。`agents/*/AGENT.md`、`mcp_servers.yaml`、`AGENTS.md`/`SOUL.md`/`USER.md` 只能逐次确认，不提供会话级授权。
- **FR-003**：shell 接受程序名（按 AgentOS 进程的 PATH 解析）或绝对路径，以真实路径授权；cwd 须在工作区内，预授权命令可使用其 `allowed-cwds`。临时授权的命令只继承 `inherit-environment` 中的基础变量，预授权命令另加其显式 env。
- **FR-004**：http_get / http_post 可访问任意 http/https 地址；白名单外的域名需确认。非 http/https、带 userinfo 的地址硬拒绝。
- **FR-005**：部署者配置的端点（搜索 endpoint、`mcp_servers.yaml` 中的 MCP 程序与 URL）视为已授权，不要求再列入白名单；本地 MCP 不再复用 shell 命令白名单。
- **FR-006**：CLI 授权提示与提问共用同一个输入 broker；超时或取消后的迟到输入被丢弃并提示，不能成为授权或聊天输入。
- **FR-007**：拒绝、无人值守、超时均以 `sandbox_denied` 审计并作为工具错误结果交给模型；日志只记录动作类别与决定，不记录路径、参数、URL。
- **FR-008**：等待授权期间整轮与工具计时暂停，取消与租约检查照常生效；`agent_turns.deadline_at` 随续租同步后移；从未暂停的轮次不改库内截止时间。

## Success Criteria

- **SC-001**：拒绝、无人值守、超时样本的真实文件写入、进程启动、HTTP 请求数均为 0。
- **SC-002**：同一会话授权后第二次同类动作的询问次数为 0；跨会话询问次数为 1。
- **SC-003**：2 秒整轮预算的 Agent 在 3 秒人工等待后仍以 SUCCEEDED 结束。
- **SC-004**：`mvn -B verify` 全部通过，005 的既有门禁不放宽。

## 不做

授权持久化到配置文件、按 Agent 收窄资源范围、命令前缀/通配规则语言、操作系统级沙箱、HTTP 入口的授权交互（契约已预留，后续实现）。

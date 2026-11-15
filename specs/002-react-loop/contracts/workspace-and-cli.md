# Contract: 工作区、AGENT.md 与命令行

**Owner**: `AgentOS-cli`（命令）、`AgentOS-core`（`AgentLoader`、`ContextLoader`） | **Spec**: FR-024 ~ FR-027、FR-039 ~ FR-042

## AgentOS init

在当前目录创建工作区，不连接数据库，也不启动应用容器（FR-039）。已存在的目录和文件一律不覆盖，重复运行得到同样的结果。

```text
.AgentOS/
├── agents/              # 每个子目录是一个 Agent
├── skills/              # 公共 Skill（US-4 才使用，这里只建目录）
├── output/
├── memory/MEMORY.md     # 只建空文件，US-3 才读写
├── logs/
├── AGENTS.md            # Bootstrap：项目级行为说明
├── SOUL.md              # Bootstrap：默认人格
└── USER.md              # Bootstrap：用户偏好
```

三个 Bootstrap 文件由内置模板生成，内容是简短的中文说明和占位。命令结束时列出"新建了哪些、跳过了哪些"。

## AGENT.md

`.AgentOS/agents/<name>/AGENT.md`：以 `---` 开头的 YAML frontmatter，之后是正文，也就是这个 Agent 的任务指令。目录名就是 Agent 名。每一轮开始时重新读取，不缓存（FR-024）。

| frontmatter 键 | 必填 | 说明 |
|----------------|------|------|
| `name` | 是 | 必须等于目录名 |
| `description` | 否 | |
| `provider.name` | 否 | 不写用默认连接 |
| `provider.model` | **是** | 聊天模型名（US-1 起连接不再配模型） |
| `provider.reasoning_effort`、`provider.max_output_tokens`、`provider.temperature` | 否 | 没写的不发给模型（FR-025） |
| `tools` | 否 | 可用工具名单；没写等于空，一个工具都不能用（Clarifications） |
| `settings.max_iterations` | 否 | 默认 10 |
| `settings.max_history_turns` | 否 | 默认 20 |
| `settings.max_history_tokens` | 否 | 默认 32000 |
| `settings.turn_timeout` | 否 | 默认 300s |

`identity`、`mcp_servers`、`channels`、`schedules`、`bootstrap`、`settings.tool_search` 等键本版本认识但不支持，打一条 WARN，不报错。Bootstrap 固定读 `AGENTS.md`、`SOUL.md`、`USER.md`，缺失的打 WARN 并跳过（FR-024）。

## AgentOS chat

```text
AgentOS chat [--profile <name>] [--message "<文本>"] [--new]
```

- **选 Agent**（FR-040）：没写 `--profile` 时，只有一个 Agent 就用它；有多个就列出名单并要求指定；一个都没有，提示先运行 `AgentOS init` 并创建 `agents/<name>/AGENT.md`。
- **启动**：用 `ContextLauncher` 启动无 Web 容器的应用上下文，执行启动收尾，再进入对话（research R12）。数据库连接缺失或不可用，打印一句话的原因，不发出任何模型请求。
- **会话标识**：渠道 `cli`，用户标识取本机登录用户名，加 Agent 名（FR-041）。默认接着已有的活跃会话。
- **`--new`**：先把该 Agent 当前的活跃会话归档，再开始新会话。有轮次正在运行时拒绝，提示"上一条消息还在处理"，不归档。当前没有活跃会话时直接开始。
- **`--message`**：发这一条、打印回答后退出。省略时进入交互循环，每行输入一条消息，输入 `/exit` 或 EOF（Ctrl-D）结束。空行不创建轮次，直接提示（FR-042）。
- **输出**：回答打印到标准输出；错误说明打印到标准错误，不含内部细节、堆栈和密钥。
- **日志**：控制台只显示 WARN 及以上，避免打断对话；完整日志照常写文件。

| 退出码 | 含义 |
|--------|------|
| 0 | 正常结束；用 `--message` 时轮次为 `SUCCEEDED` |
| 1 | 启动失败（数据库、配置） |
| 2 | 用法错误：没有可用的 Agent、`AGENT.md` 不合法、消息为空 |
| 3 | 用 `--message` 时轮次没有成功：失败、超时、达到上限、中断、会话忙 |

**中断**：交互时按 Ctrl-C，注册的关闭钩子尽力释放会话租约并把轮次收尾为 `INTERRUPTED`。来不及时，下次启动由收尾处理；租约到期之前再发消息，会提示"上一条消息还在处理"。

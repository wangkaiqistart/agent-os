# Contract: 工作区、AGENT.md 与命令行

**Owner**: `AgentOS-cli`（命令）、`AgentOS-core`（`AgentLoader`、`ContextLoader`） | **Spec**: FR-024 ~ FR-027、FR-039 ~ FR-042

**Updated**: 2026-11-16，默认主 Agent 增量已在 Phase 10 实施；跨轮历史与 `--new` 已在 Phase 6 接入，证据见 quickstart 第 12 节。US5/US6 的故障恢复与并发加固仍待后续阶段。

## AgentOS init

```text
AgentOS init [--workspace <路径>] [--provider <name>] [--model <model>]
```

工作区默认是当前目录下的 `.AgentOS`；`--workspace` 指向工作区根目录本身，不再额外拼接 `.AgentOS`。相对路径相对于命令执行目录解析。初始化不连接数据库或模型，也不启动应用容器（FR-039）。已存在的目录和文件一律不覆盖，重试只补缺失项。

```text
.AgentOS/
├── agents/              # 每个子目录是一个 Agent
│   └── main/AGENT.md    # 默认主 Agent；普通 Profile，默认无工具权限
├── skills/              # 公共 Skill（US-4 才使用，这里只建目录）
├── output/
├── memory/MEMORY.md     # 创建简短中文模板；长期记忆能力由后续 story 实现
├── logs/
├── AGENTS.md            # Bootstrap：项目级行为说明
├── SOUL.md              # Bootstrap：默认人格
└── USER.md              # Bootstrap：用户偏好
```

三个 Bootstrap 文件由内置模板生成，内容是简短的中文说明和占位。命令结束时列出"新建了哪些、跳过了哪些"。

### 默认主 Agent 的生成

- `main` 是固定的默认名称，正文为通用中文助手指令。采用下节已有的 frontmatter 契约，包含 `name: main`、具体 `provider.model` 和 `tools: []`；不默认写入温度等模型专用参数，运行上限沿用已有默认值。
- `--provider <name>` 只写入 `provider.name`；省略时不写该键，由聊天运行时的默认连接配置决定（当前默认 `primary`）。显式空白名称属于用法错误；初始化不联网检查连接名或模型是否存在。
- 创建缺失的 `main/AGENT.md` 时，模型名按下表取第一个**已提供**的来源；空白或含未解析 `${...}` 等占位符的所选值是错误，不得继续回退。不得把开发者测试模型写成内置默认值。

| 优先级 | 来源 | 边界 |
|--------|------|------|
| 1 | `--model <model>` | 显式参数优先 |
| 2 | 当前进程环境变量 `AGENTOS_LLM_MODEL` | 仅作为新 Agent 的模型名输入 |
| 3 | 命令执行目录 `.env` 中的 `AGENTOS_LLM_MODEL` | 按现有 `.properties` 配置语义读取同名项；不执行 shell、不解析其他变量、不导入或输出密钥项；文件不存在视为未提供 |

模型名仅在初始化时解析并写成具体值；不新增 `AGENT.md` 运行时环境变量插值或 Provider 连接中的默认模型。读取所选来源失败时报告安全错误，不继续回退；低优先级来源在高优先级来源已提供时不参与解析。自定义 `--workspace` 不改变 `.env` 的查找位置；模型名只代表用户的选择，不表示已验证服务支持它。

模型名来源的编码、转义、重复键和错误分类见 [configuration.md](configuration.md)。生成 frontmatter 时对 model/provider 做 YAML 字符串序列化，不将输入当 YAML 结构；本地字段校验复用 AgentLoader，不检查外部连接可用性。

首次创建前，若模型名缺失或无效，返回退出码 2，提示通过 `--model` 或 `AGENTOS_LLM_MODEL` 补齐，不写入任何工作区文件、不打印原始配置值。已有合法 `main` 时跳过该文件，不要求新的模型输入，即使传入不同模型也不修改它；缺失的其他模板仍补齐。已有 `main` 不合法时返回 2，指出文件及字段，保留原内容，不能输出“已就绪”。本地校验只覆盖本版本支持的文件结构与字段，不证明 Provider 或数据库可用。

目录存在但 `main/AGENT.md` 缺失时，仍按首次创建规则补齐。路径被普通文件占用、无写入权限或文件 I/O 失败，返回 1 并说明未完成；已经成功创建的文件保留，再次执行不得覆盖。结束输出区分新建、跳过、失败，并说明初始化只准备本地文件，聊天还需要已有的 Provider、凭据与数据库配置。任何生成文件或输出均不得复制连接地址、模型密钥、数据库密码或 `.env` 原文。

受管理目录/文件必须满足预期类型，不能通过冲突软链接写入其他位置。文件以 create-new 语义创建；竞争者已经写入时不覆盖，检查赢家内容/类型后跳过或安全报错。初始化不承诺跨文件事务或自动修复损坏文件，写坏的已有 main 需人工处理。默认主 Agent 未完成前不能报告“初始化成功”。

工作区根部的 `.AgentOS/AGENTS.md` 是所有 Agent 共享的 Bootstrap 行为指令，内容进入模型上下文；它不承担模型、工具名单或默认 Agent 的配置解析。默认主 Agent 的 Profile 仍来自 `agents/main/AGENT.md`，保留多 Agent 目录结构。

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
| `settings.max_iterations` | 否 | 默认10，整数≥1 |
| `settings.max_history_turns` | 否 | 默认20，整数≥0；0不带历史 |
| `settings.max_history_tokens` | 否 | 默认32000，整数≥0；0不带历史，正值按90% floor使用 |
| `settings.turn_timeout` | 否 | 默认300s，正Duration |

`identity`、`mcp_servers`、`channels`、`schedules`、`bootstrap`、`settings.tool_search` 等键本版本认识但不支持，打一条 WARN，不报错。Bootstrap 固定读 `AGENTS.md`、`SOUL.md`、`USER.md`，缺失的打 WARN 并跳过（FR-024）。

## AgentOS chat

```text
AgentOS chat [--workspace <路径>] [--profile <name>] [--message "<文本>"] [--new]
```

- **选 Agent**（FR-040）：显式 `--profile` 优先；未指定时优先 `main`；没有 `main` 时，只有一个 Agent 就用它，有多个就列出名单并要求指定，一个都没有就提示先运行 `AgentOS init` 初始化默认主 Agent。候选 Agent 以 `agents/` 下直接子目录为范围，不把普通文件当 Agent；选中后必须校验 `AGENT.md`。显式 Agent 不存在或不合法，以及 `main` 目录存在但文件缺失或不合法，均返回用法错误，不静默切换到其他 Agent，不发送模型请求。
- **工作区一致性**：`--workspace` 的默认值和路径含义与 `init` 相同，必须同时用于候选扫描、实际 Agent 加载与 Bootstrap 加载；不能仅扫描指定目录后仍从默认目录加载。不得读取另一个工作区的文件作为隐式回退。
- **启动器端口**：CLI 的 `ContextLauncher.launch(Path workspaceRoot)` 接收规范化绝对路径；boot 用高优先级 `--agentos.workspace.root=<path>` 启动 NONE 上下文，两个加载器复用同一 root。选中 Agent 的文件/字段本地校验在启动器前完成；无效选择不启动上下文、不调用模型。Provider/数据库错误仍由运行时安全报告。
- **启动**：用 `ContextLauncher` 启动无 Web 容器的应用上下文，执行启动收尾，再进入对话（research R12）。数据库连接缺失或不可用，打印一句话的原因，不发出任何模型请求。
- **会话标识**：渠道 `cli`，用户标识取本机登录用户名，加 Agent 名（FR-041）。默认接着已有的活跃会话。
- **`--new`**：启动时先按 CLI 会话键把该 Agent 当前的活跃会话归档，再由 `startTurn` 创建新 UUID 会话。有轮次正在运行时拒绝，提示"上一条消息还在处理"，不归档；当前没有活跃会话时直接开始。归档只更新会话状态/时间和租约，不修改旧轮次、消息或审计。
- **`--message`**：发这一条、打印回答后退出。省略时进入交互循环，每行输入一条消息，输入 `/exit` 或 EOF（Ctrl-D）结束。空行不创建轮次，直接提示（FR-042）。
- **输出**：回答打印到标准输出；错误说明打印到标准错误，不含内部细节、堆栈和密钥。
- **日志**：控制台只显示 WARN 及以上，避免打断对话；完整日志照常写文件。

| 退出码 | 含义 |
|--------|------|
| 0 | 正常结束；用 `--message` 时轮次为 `SUCCEEDED` |
| 1 | 启动失败（数据库、配置） |
| 2 | 用法错误：没有可用的 Agent、`AGENT.md` 不合法、消息为空 |
| 3 | 用 `--message` 时轮次没有成功：失败、超时、达到上限、中断、会话忙 |

**中断**：交互时按 Ctrl-C，注册的关闭钩子尽力释放会话租约并把轮次收尾为 `INTERRUPTED`。来不及时，数据库恢复且租约过期后，在下次启动或会话接管时回收，不要求deadline已过；租约有效时新请求提示忙。默认租约至少到本轮deadline加30秒，强杀后等待可能超过150秒。

数据库收尾无法确认时，CLI明确打印turnId和“持久化未确认”；--message退出码3，不将它显示为成功或声称租约已释放。

## 默认主 Agent 的验收映射

下表为新增目标行为，不能用旧任务的完成标记替代运行验证。聊天端到端验收采用测试数据库和模型替身；真实模型验收仍须显式启用。

| 范围 | Spec 场景（US1） | 成功标准 |
|------|----------------|----------|
| 首次生成、离线初始化、无需手写即可聊天 | 7、8 | SC-018 |
| 模型名来源、无效输入、幂等与损坏配置 | 9、10 | SC-013、SC-019 |
| 显式选择、默认 `main`、旧工作区回退与失败 | 11、12 | SC-020 |
| 自定义工作区贯穿初始化、选择及加载 | 13 | SC-021 |
| 非敏感配置、输出安全、路径与写入失败 | 14、Edge Cases | SC-022 |

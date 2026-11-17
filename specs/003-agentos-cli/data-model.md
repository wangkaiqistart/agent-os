# Data Model: AgentOS CLI 入口与会话

**Feature**: [spec.md](spec.md)

本功能复用 AgentOS 现有运行时实体，不新增 CLI 专用数据库表。下列模型描述 CLI 输入如何映射到工作区文件和 core/storage 实体。

## Workspace

用户可读写的本地工作区根目录。默认是命令执行目录下的 `.AgentOS`；`--workspace` 指向工作区根目录本身，相对路径相对命令执行目录解析，入口统一规范化为绝对路径。

| 字段/组成 | 规则 |
|---|---|
| `root` | 非空、绝对、规范化 Path；同一次 `init`/`chat` 的扫描、加载和启动必须共用 |
| `agents/` | 直接子目录集合，每个可选 Agent 目录包含 `AGENT.md` |
| `agents/main/AGENT.md` | 初始化生成的默认 Agent；名称固定为 `main` |
| `skills/`, `output/`, `memory/`, `logs/` | 初始化创建的工作区目录 |
| `AGENTS.md`, `SOUL.md`, `USER.md`, `memory/MEMORY.md` | 初始化补齐的共享引导/记忆文件；已有文件不覆盖 |

生命周期：不存在 → 初始化预检 → 创建缺失项 → 校验默认 Agent → 可供聊天使用。路径类型冲突、不可写或中途 I/O 失败返回退出码 1；已有损坏文件不自动覆盖或修复。

## Agent Profile

由 `agents/<name>/AGENT.md` 的 frontmatter 和正文构成，使用 `AgentLoader` 校验后交给运行时。

| 字段 | 规则 |
|---|---|
| `name` | 必填，必须等于目录名；目录名不得含路径穿越片段 |
| `provider.name` | 初始化默认写 `primary`；显式 Provider 必须非空且由运行时配置支持 |
| `provider.model` | 初始化必须来自显式 `--model`，具体、非空白、无未解析占位符 |
| `tools` | 默认空列表；初始化不授予工具权限 |
| 正文 | 作为 Agent 指令；CLI 不拼接或改写 |

Agent 选择顺序：显式 `--profile` → `main` → 唯一直接子目录。显式选择不存在/损坏，或存在的 `main` 损坏，均返回退出码 2，不回退。

## Provider Reference

Provider 只是 Agent Profile 对运行时连接名称的引用，不在 CLI 工作区保存 URL、API key 或其他凭据。

| 状态 | 规则 |
|---|---|
| 未指定 Provider | 初始化生成 `provider.name: primary` |
| 显式 Provider | 先校验非空名称；未知 Provider 在写入前返回退出码 2 |
| 名称目录来源 | `--config` 显式路径最高优先级；未提供时依次查找当前目录 `application.yaml`、`config/application.yaml`、`$XDG_CONFIG_HOME/agentos/application.yaml`、`~/.config/agentos/application.yaml`、classpath `application.yaml`。`ProviderNameProjectionLoader` 只读取最终配置中的 `agentos.providers` map key，`LocalProviderNameCatalog` 只暴露名称集合；不绑定或构建模型客户端，不读取 URL、凭据或 `.env` |
| 外部连接不可用 | `init` 不探测；`chat` 由运行时启动失败返回退出码 1 |

## SessionKey

CLI 将统一运行时会话定位键构造成：

```text
(channel="cli", userId=current operating-system account, profileName=<selected agent>)
```

v1 不提供命令行或环境变量覆盖 `userId`。同一键复用活跃会话；任一维度变化都必须隔离历史。底层会话实体使用现有 UUID 主键和 active/archived 生命周期。

## Turn

一次用户消息从提交到最终结果的运行时处理单元。CLI 不直接创建或更新 Turn，而是传入 `ProcessRequest(channel, userId, agentName, message, trigger="cli")`。

| 状态/结果 | CLI 表现 |
|---|---|
| `SUCCEEDED` 且持久化已确认 | 输出回答，单条消息返回 0 |
| 失败、超时、达到上限、中断、会话忙 | 错误输出；单条消息返回 3 |
| 持久化 `UNCONFIRMED` | 输出安全提示和 `turnId`，单条消息返回 3 |
| 交互模式可继续的失败 | 即时错误输出，继续读取输入；正常 `/exit`/EOF 最终返回 0 |

## CliExecution

一次命令进程的控制状态，不持久化为新表。

```text
PARSED
  ├─ usage/configuration error → EXIT_2
  ├─ init → LOCAL_WORKSPACE_READY → EXIT_0
  └─ chat → PROFILE_VALIDATED → CONTEXT_STARTED
       ├─ startup failure → EXIT_1
       ├─ single message → TURN_RESULT → EXIT_0/EXIT_3
       └─ interactive input → TURN_RESULT* → NORMAL_END → EXIT_0
```

正常输出流只承载回答和成功初始化摘要；错误流承载参数、配置、启动、轮次和持久化诊断，不打印密钥、prompt、工具参数或异常堆栈。

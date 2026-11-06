---
title: 定义业务 Agent
lang: zh-CN
description: 用 AGENT.md 目录、Skill、脚本和定时规则定义一个业务 Agent。
---

# 定义业务 Agent {#define-agent}

在 AgentOS 中，Agent 不是一组 Java 类，而是 `.AgentOS/agents/<name>/` 下的一个目录。底座能力由所有 Agent 共享，目录决定这个 Agent 做什么、怎么运行以及能看到哪些资源。

## AGENT.md 是入口 {#agent-md}

一个最小 Agent 至少包含 `AGENT.md`：

```markdown
---
name: daily-weather
description: 每天早上查天气并推送穿搭建议
provider:
  name: primary
  reasoning_effort: low
tools: [http_get, notify]
settings:
  max_iterations: 10
  max_history_turns: 20
---

查询北京当天天气，给出简短的穿搭建议，然后通过通知渠道 team-lark 推送。
```

frontmatter 派生为 Profile，正文进入 system prompt。Provider、工具和调度规则来自配置，任务指令由 Agent 作者用自然语言描述。

## 渐进式披露 {#progressive-disclosure}

Agent 目录可以包含：

```text
.AgentOS/agents/daily-weather/
├── AGENT.md
├── skills/       # 指向公共 Skill 的相对软连接
├── scripts/      # 可信部署中的脚本
└── REFERENCE.md  # 参考资料
```

每轮只注入绑定 Skill 的名称、描述和本地路径。正文、参考资料和脚本按需通过已有的 `read_file` / `shell` 工具读取。Skill 不是 Tool，不会进入 ToolRegistry。

## 工作区 {#workspace}

`AgentOS init` 的目标工作区结构如下：

```text
.AgentOS/
├── agents/             # Agent 目录
├── skills/             # 公共 Skill 实体库
├── memory/MEMORY.md    # 默认长期记忆
├── output/             # 产出物
├── logs/               # 日志
├── AGENTS.md           # 项目级 Bootstrap
├── SOUL.md             # 默认人格
├── USER.md             # 用户偏好
└── mcp_servers.yaml    # MCP 配置
```

当前仓库仍处于骨架和设计阶段；`init` 的完整实现属于后续 user story。

## 多个入口 {#triggers}

同一个 Agent 可以被三种方式触发：

- `AgentOS chat`：本地交互；
- REST `invoke`：业务系统无状态调用；
- `schedules`：按 cron 自动触发。

三种入口共享相同的 Session、Turn、ReAct 和审计流程。

## 目录不是沙箱 {#trust-boundary}

`AGENT.md` 目录和脚本不天然获得操作系统级隔离。通用 `shell` 如果允许调用 Python、Bash 或 Node，等价于给模型授予 AgentOS 进程的系统权限。可信单机可以使用；不可信代码或多租户场景需要未来的容器或 microVM Runner。

## 设计依据 {#sources}

- [需求文档 5.1、5.2（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
- [技术方案第 8、11 章（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)

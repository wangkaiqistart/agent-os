---
title: Define an Agent
lang: en-US
description: Define a business Agent with AGENT.md, Skills, scripts, and schedules.
---

# Define an Agent {#define-agent}

In AgentOS, an Agent is not a set of Java classes. It is a directory under `.AgentOS/agents/<name>/`. The runtime is shared; the directory defines what the Agent does, how it runs, and which resources it can see.

## AGENT.md is the entry point {#agent-md}

A minimal Agent contains `AGENT.md`:

```markdown
---
name: daily-weather
description: Check the weather every morning and send an outfit suggestion
provider:
  name: primary
  reasoning_effort: low
tools: [http_get, notify]
settings:
  max_iterations: 10
  max_history_turns: 20
---

Check Beijing weather, write a short outfit suggestion, and send it through the team-lark notification channel.
```

Frontmatter is derived into a Profile and the body becomes part of the system prompt. Provider, tools, and schedules come from configuration; the Agent author describes the task in natural language.

## Progressive disclosure {#progressive-disclosure}

An Agent directory can contain:

```text
.AgentOS/agents/daily-weather/
├── AGENT.md
├── skills/       # controlled relative links to shared Skills
├── scripts/      # scripts for trusted deployments
└── REFERENCE.md  # reference material
```

Only the name, description, and local path of a bound Skill enter each prompt. Its body, references, and scripts are read on demand through existing `read_file` / `shell` tools. A Skill is context, not a Tool, and is not registered in ToolRegistry.

## Workspace {#workspace}

The target workspace created by `AgentOS init` looks like this:

```text
.AgentOS/
├── agents/             # Agent directories
├── skills/             # shared Skill entities
├── memory/MEMORY.md    # default long-term memory
├── output/             # generated artifacts
├── logs/               # logs
├── AGENTS.md           # project Bootstrap
├── SOUL.md             # default identity
├── USER.md             # user preferences
└── mcp_servers.yaml    # MCP configuration
```

The repository is still at the skeleton/design stage; the complete `init` implementation belongs to a later user story.

## Multiple triggers {#triggers}

The same Agent can be triggered in three ways:

- `AgentOS chat`: local interaction;
- REST `invoke`: a stateless business-system call;
- `schedules`: a cron-triggered Turn.

All three share the same Session, Turn, ReAct, and audit path.

## A directory is not a sandbox {#trust-boundary}

An `AGENT.md` directory and its scripts do not automatically receive operating-system isolation. Allowing the generic `shell` Tool to invoke Python, Bash, or Node gives the model the permissions of the AgentOS process. It can be acceptable in a trusted single-node deployment; untrusted code and multi-tenancy require a future container or microVM Runner.

## Design basis {#sources}

- [Requirements, Sections 5.1 and 5.2 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
- [Technical solution, Chapters 8 and 11 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)

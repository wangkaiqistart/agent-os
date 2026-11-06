---
title: Roadmap and contributing
lang: en-US
description: AgentOS user stories, acceptance demos, extension boundaries, and contribution guide.
---

# Roadmap and contributing {#roadmap}

AgentOS first hardens the runtime kernel, then grows the enterprise governance layer. This roadmap describes the design sequence, not a claim that every item is complete today.

## Core stage {#core-stage}

```text
US-1 Provider
   ↓
US-2 ReAct
   ↓
US-3 Memory  ──┐
               ├── US-5 Web Service
US-4 Tool    ──┘
```

- **US-1**: explicit model Provider mapping, timeouts, and call metadata;
- **US-2**: ReActLoop, PromptBuilder, Turns, tool execution, and a CLI demo;
- **US-3**: conversational memory, long-term memory, and Memory Tools;
- **US-4**: built-in Tools, MCP, Sandbox, and the governance wrapper;
- **US-5**: PostgreSQL persistence, 10 REST endpoints, scheduling, and engineering close-out.

The core acceptance scenarios are a daily weather report, a daily technology digest, and a daily GitHub digest. They demonstrate a bare `AGENT.md`, a shared Skill, and script resources respectively.

## Later directions {#later}

- More IM Channels: Feishu, WeCom, DingTalk, and others;
- Complete Tool Policy, container/microVM sandboxing, and RBAC;
- SSE, an administration UI, audit queries, and cost dashboards;
- SSO, multi-tenancy, multi-instance deployment, and high availability;
- Knowledge bases, episodic memory, and richer semantic retrieval.

These capabilities require design, implementation, tests, and security review. They are not claimed as supported by the current skeleton.

## Contributing {#contributing}

1. Read the [technical solution (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md) and the relevant pages here;
2. Prefer official Spring AI 2.0 components; do not introduce Spring AI Alibaba or SQLite/H2;
3. Preserve the rules: self-owned loop, one tool execution entry point, and audit from day one;
4. Update the Chinese and English docs and homepage status when changing a capability;
5. Add tests and document verification. Never commit `.env`, API keys, or database passwords.

## Security boundary {#security}

The core-stage `WhitelistSandbox` is an application-level anti-misoperation boundary, not full isolation for untrusted code. If a credential leaks, revoke it immediately, then clean Git history and notify maintainers; never place credentials in Issues or Pull Requests.

## Design documents {#design-docs}

- [Industry research (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/IndustryResearch.md)
- [Requirements (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
- [Technical solution (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [AI programming guide (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/AiProgrammingGuide.md)

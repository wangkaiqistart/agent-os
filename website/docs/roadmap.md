---
title: 路线图与贡献
lang: zh-CN
description: AgentOS 的 user story 路线、验收 Demo、扩展边界与贡献方式。
---

# 路线图与贡献 {#roadmap}

AgentOS 先把运行时内核做扎实，再逐步补企业治理层。下面是设计路线，不是对当前完成度的承诺。

## 核心阶段 {#core-stage}

```text
US-1 Provider
   ↓
US-2 ReAct
   ↓
US-3 Memory  ──┐
               ├── US-5 Web Service
US-4 Tool    ──┘
```

- **US-1**：模型 Provider 显式映射、超时和调用元数据；
- **US-2**：ReActLoop、PromptBuilder、Turn、工具执行和 CLI Demo；
- **US-3**：会话记忆、长期记忆和 Memory Tools；
- **US-4**：内置 Tool、MCP、Sandbox、治理装饰器；
- **US-5**：PostgreSQL 持久化、10 个 REST 端点、调度与工程化收尾。

核心验收场景是每日天气、每日科技日报和每日 GitHub 日报，分别演示光杆 `AGENT.md`、公共 Skill 和脚本资源。

## 之后的方向 {#later}

- 更多 IM Channel：飞书、企业微信、钉钉等；
- 完整 Tool Policy、容器/microVM 沙箱和 RBAC；
- SSE、管理台、审计查询和成本看板；
- SSO、多租户、多实例与高可用；
- 知识库、情景记忆和更完整的语义检索。

这些能力需要经过设计、实现、测试和安全评审，不会在当前骨架中提前宣称已支持。

## 如何贡献 {#contributing}

1. 先读 [技术方案（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md) 和本网站的相关页面；
2. 保持 Spring AI 2.0 官方组件优先、不引入 Spring AI Alibaba、不使用 SQLite/H2；
3. 遵守“循环自实现、工具单一执行入口、审计从第一天落库”等约束；
4. 修改功能时同步更新中英文文档和首页状态；
5. 补测试并说明验证方式，不提交 `.env`、API key 或数据库密码。

## 安全边界 {#security}

核心阶段的 `WhitelistSandbox` 是应用层防误操作边界，不是运行不可信代码的完整隔离。发现密钥泄露时应立即吊销密钥，再清理 Git 历史并通知维护者；不要把凭证提交到 Issue 或 Pull Request。

## 设计资料 {#design-docs}

- [行业调研（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/IndustryResearch.md)
- [需求文档（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/DemandAnalysis.md)
- [技术方案（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [AI 编程指南（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/AiProgrammingGuide.md)

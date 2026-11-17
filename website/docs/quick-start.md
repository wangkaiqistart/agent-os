---
title: 快速开始
lang: zh-CN
description: 构建并运行 AgentOS init/chat，配置默认 Agent、工作区和新会话。
---

# 快速开始 {#quick-start}

当前可以运行 `init`、`chat`、单消息和 `--new`。Provider、ReAct、HTTP GET 与会话/审计持久化已实现；长期记忆、完整 Tool/MCP、REST、调度、SSE 和管理命令仍待后续。

## 环境要求 {#requirements}

- JDK21、Maven3.9+、Git；
- 运行 chat：独立 PostgreSQL16+ 和可用的 OpenAI 兼容 Provider；
- 数据库自动化测试：Docker 与 `pgvector/pgvector:pg17`。US-2 的五表迁移本身不依赖 vector 扩展。

## 构建与验证 {#build}

```bash
git clone https://github.com/wangkaiqistart/agent-os.git
cd agent-os
mvn -B verify
python3 scripts/check_db_test_reports.py
```

有 Docker 时数据库测试实际执行，本地无 Docker 会明确跳过，CI 不允许跳过。默认验证不调用真实模型。产物为 `AgentOS-boot/target/AgentOS.jar` 与独立文件命令入口 `AgentOS-cli/target/AgentOS-cli-exec.jar`；chat 使用完整 boot JAR。

## 配置连接 {#configuration}

复制根目录 `.env.example` 为未跟踪的 `.env`，填入 `AGENTOS_DB_URL/USERNAME/PASSWORD`、`AGENTOS_LLM_BASE_URL/API_KEY/MODEL`。连接地址写到服务实际的 `/v1`；模型名将写入 Agent，连接和密钥不写入 Agent。不要提交或打印凭据。首次连接数据库时 Flyway 会执行迁移，先确认目标库与建表权限，禁止对共享库执行 clean。

US-2 不需要 Embedding，运行时可设置 `AGENTOS_EMBEDDING_ENABLED=false`。详细连接约束见 [模型与配置](/docs/models)。

## 初始化并聊天 {#verify}

```bash
java -jar AgentOS-boot/target/AgentOS.jar init --workspace .AgentOS --model <model-name>
java -jar AgentOS-boot/target/AgentOS.jar chat --workspace .AgentOS --message "你好"
java -jar AgentOS-boot/target/AgentOS.jar chat --workspace .AgentOS
java -jar AgentOS-boot/target/AgentOS.jar chat --workspace .AgentOS --new --message "开始新话题"
```

将 `<model-name>` 换成服务支持的模型。省略 `--model` 时，初始化依次查进程 `AGENTOS_LLM_MODEL` 和执行目录 `.env` 的同名项。缺少或无效模型会在写文件前失败；已有合法 main 不被覆盖。init 只准备本地文件，不启动 Spring、不连接数据库或模型。

chat 优先显式 `--profile <name>`，否则 main，再兼容旧工作区唯一 Agent。main 默认 `tools: []`，不会联网取天气；天气 Agent 需要显式授权 `http_get` 和域名白名单。每条消息重读 Agent/Bootstrap，输入 `/exit` 或 EOF 退出。

`--new` 只在空闲时归档，不删除旧历史。有轮次运行时提示“上一条消息还在处理”。关闭再打开 chat 默认恢复同一登录用户的活跃会话。

## 结果与边界 {#target}

单消息成功退出0，启动失败1，用法错误2，轮次不成功或会话忙3。`UNCONFIRMED` 带 turnId，表示持久化尚未确认，不能按成功处理。强杀后的有效租约可能需要等待到期。

`serve`、`gateway`、profile/provider/tool/session 管理命令，以及长期记忆、MCP、正式 SSE API 仍是规划。真实模型与 met.no Demo 会收费并发送天气上下文，需显式启用；[ReAct 验收说明](/docs/react-loop#validation)区分脚本替身、真实模型和真实数据源。

## 下一步 {#next}

- [ReAct 与轮次](/docs/react-loop)
- [总体架构](/docs/architecture)
- [路线图与贡献](/docs/roadmap)

---
title: 快速开始
lang: zh-CN
description: 构建 AgentOS Maven 骨架并运行 CLI 版本入口。
---

# 快速开始 {#quick-start}

> 当前仓库是 Maven 骨架。下面的命令可以验证 Java、Maven 和 CLI 打包；`chat`、`serve`、`gateway` 属于后续实现目标。

## 环境要求 {#requirements}

- JDK 21+
- Maven 3.9+
- Git
- 如果后续运行完整 AgentOS：PostgreSQL 16+ 和 pgvector

## 构建骨架 {#build}

```bash
git clone https://github.com/wangkaiqistart/agent-os.git
cd agent-os
mvn -B clean package
```

构建产物：

- `AgentOS-boot/target/AgentOS.jar`：完整应用目标包；
- `AgentOS-cli/target/AgentOS-cli-exec.jar`：可单独运行的 CLI 目标包。

## 验证 CLI 入口 {#verify}

```bash
java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --version
java -jar AgentOS-boot/target/AgentOS.jar --version
```

两个入口共享 Picocli 命令解析，版本输出包含 AgentOS、Spring Boot、Spring AI 和 Java 版本。当前可验证的命令是：

```bash
java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --help
java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --version
```

## 目标形态（尚未全部实现） {#target}

以下命令已经写入设计方案，但业务实现还在 US-1 到 US-5 的实施计划中：

```bash
AgentOS init
AgentOS chat --profile <name>
AgentOS serve
AgentOS gateway
AgentOS profile list
AgentOS provider list
AgentOS tool list
AgentOS session list
```

不要把这些命令当作当前版本已经支持的功能。请从 [什么是 AgentOS](/docs/what) 了解目标边界，再看 [总体架构](/docs/architecture)。

## 配置原则 {#configuration}

真实开发环境会使用项目方提供的 PostgreSQL、主链路模型和 Embedding 服务。密钥只写在本地 `.env`，不要提交到仓库；模板见根目录 `.env.example`。

主链路模型使用 `primary` Provider，测试环境是 OpenAI 兼容中转上的 `gpt-5.6-luna`；Embedding 独立使用百炼 `text-embedding-v4`，默认 1024 维。详细配置见 [模型与配置](/docs/models)。

## 下一步 {#next}

- 想理解产品边界：读 [什么是 AgentOS](/docs/what)；
- 想理解模块与数据流：读 [总体架构](/docs/architecture)；
- 想参与实现：读 [路线图与贡献](/docs/roadmap)。

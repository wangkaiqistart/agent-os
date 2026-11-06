---
title: Quick start
lang: en-US
description: Build the AgentOS Maven skeleton and run its CLI version entry point.
---

# Quick start {#quick-start}

> The repository is currently a Maven skeleton. These commands verify Java, Maven, and the CLI packaging; `chat`, `serve`, and `gateway` are later runtime targets.

## Requirements {#requirements}

- JDK 21+
- Maven 3.9+
- Git
- PostgreSQL 16+ with pgvector when the full AgentOS runtime is implemented

## Build the skeleton {#build}

```bash
git clone https://github.com/wangkaiqistart/agent-os.git
cd agent-os
mvn -B clean package
```

Build outputs:

- `AgentOS-boot/target/AgentOS.jar`: target package for the full application;
- `AgentOS-cli/target/AgentOS-cli-exec.jar`: standalone CLI target package.

## Verify the CLI entry points {#verify}

```bash
java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --version
java -jar AgentOS-boot/target/AgentOS.jar --version
```

Both entry points share the same Picocli command parser. Version output includes AgentOS, Spring Boot, Spring AI, and Java versions. The commands currently verifiable are:

```bash
java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --help
java -jar AgentOS-cli/target/AgentOS-cli-exec.jar --version
```

## Target commands (not all implemented yet) {#target}

These commands are part of the design, but their runtime implementations are being delivered through US-1 to US-5:

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

Do not treat these commands as supported by the current skeleton. Read [What is AgentOS](/en/docs/what) for the boundary, then [Architecture](/en/docs/architecture).

## Configuration principles {#configuration}

The development environment will use a project-provided PostgreSQL instance, a primary model endpoint, and a separate Embedding service. Keep secrets in a local `.env` file and never commit it; the root `.env.example` is the safe template.

The main path uses the `primary` Provider, backed in the test environment by an OpenAI-compatible `gpt-5.6-luna` endpoint. Embeddings are independent and use Alibaba Cloud Model Studio `text-embedding-v4`, with 1024 dimensions by default. See [Models and configuration](/en/docs/models).

## Next steps {#next}

- For product boundaries: read [What is AgentOS](/en/docs/what);
- For modules and data flow: read [Architecture](/en/docs/architecture);
- To contribute: read [Roadmap and contributing](/en/docs/roadmap).

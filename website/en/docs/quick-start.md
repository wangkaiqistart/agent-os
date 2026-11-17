---
title: Quick start
lang: en-US
description: Build and run AgentOS init/chat with a default Agent, custom workspace and new sessions.
---

# Quick start {#quick-start}

You can run `init`, interactive `chat`, single messages and `--new`. Provider, ReAct, HTTP GET and session/audit persistence are implemented. Long-term memory, full Tool/MCP, REST, scheduling, SSE and management commands remain future work.

## Requirements {#requirements}

- JDK 21, Maven 3.9+ and Git.
- Chat requires PostgreSQL 16+ and an available OpenAI-compatible Provider.
- Database tests require Docker and `pgvector/pgvector:pg 17`. The five US-2 tables do not require the vector extension.

## Build and validate {#build}

```bash
git clone https://github.com/wangkaiqistart/agent-os.git
cd agent-os
mvn -B verify
python 3 scripts/check_db_test_reports.py
```

Database tests execute when Docker is available, explicitly skip locally otherwise, and must execute in CI. Default validation makes no real-model requests. Outputs are `AgentOS-boot/target/AgentOS.jar` and the standalone file-command entry `AgentOS-cli/target/AgentOS-cli-exec.jar`. Use the boot JAR for chat.

## Configure connections {#configuration}

Copy `.env.example` to an untracked `.env`. Set `AGENTOS_DB_URL/USERNAME/PASSWORD` and `AGENTOS_LLM_BASE_URL/API_KEY/MODEL`. Use the service's actual `/v 1` URL. Only the model selection enters the Agent file; connection details and credentials remain external. Never commit or print credentials. Flyway migrates on first database connection, so confirm the target and migration permissions first. Never run clean against a shared database.

US-2 does not require Embedding; set `AGENTOS_EMBEDDING_ENABLED=false` at runtime. See [Models and configuration](/en/docs/models).

## Initialize and chat {#verify}

```bash
java -jar AgentOS-boot/target/AgentOS.jar init --workspace .AgentOS --model <model-name>
java -jar AgentOS-boot/target/AgentOS.jar chat --workspace .AgentOS --message "Hello"
java -jar AgentOS-boot/target/AgentOS.jar chat --workspace .AgentOS
java -jar AgentOS-boot/target/AgentOS.jar chat --workspace .AgentOS --new --message "Start a new topic"
```

Replace `<model-name>` with a supported model. Without `--model`, init checks process `AGENTOS_LLM_MODEL`, then the same key in the execution directory's `.env`. Missing or invalid input fails before writes. An existing valid main is preserved. Init prepares local files without Spring, database or model connections.

Chat selects explicit `--profile <name>`, otherwise main, then the sole Agent in older workspaces. Main has `tools: []`; a weather Agent needs explicit `http_get` permission and a domain allowlist. Agent/Bootstrap files reload each turn. Enter `/exit` or EOF to leave.

`--new` archives only an idle session and preserves old records. An active turn causes a busy response. Restarting chat resumes the current login user's active session.

## Outcomes and boundaries {#target}

Single-message exit codes are 0 for success,1 for startup failure,2 for usage errors and 3 for unsuccessful turns or busy sessions. `UNCONFIRMED` includes a turnId and means persistence is not confirmed. A valid lease after a killed process may require waiting for expiry.

`serve`, `gateway`, profile/provider/tool/session management, long-term memory, MCP and a production SSE API remain planned. Real-model and met.no Demos cost money and send weather context; they require explicit activation. [ReAct validation](/en/docs/react-loop#validation) separates scripted fixtures, real models and real data sources.

## Next steps {#next}

- [ReAct and turns](/en/docs/react-loop)
- [Architecture](/en/docs/architecture)
- [Roadmap and contributing](/en/docs/roadmap)

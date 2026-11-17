# Quickstart: AgentOS CLI 入口与会话验证

**Feature**: [spec.md](spec.md)

本指南验证工作区初始化、命令边界、会话身份、交互行为和跨进程持久化。普通验证不发送真实模型请求；真实服务只在显式 `llm` profile 下运行。

## Prerequisites

- JDK 21、Maven 和项目依赖可用。
- 运行数据库集成测试需要 Docker，以及 `pgvector/pgvector:pg17`。
- 不读取或打印真实 `.env`、API key 或数据库密码；测试使用临时目录、合成模型名和本地模型替身。

## 1. Compile and quality gate

```bash
mvn -B -ntp verify
python3 scripts/check_db_test_reports.py
```

预期：质量门通过；数据库测试在有 Docker 的环境中实际执行。Docker 不可用时可明确记录数据库装配未验证；`CI=true` 不得跳过。

## 2. US1 stage demo: Offline initialization

先准备一份 CLI 与 Boot 共用的非敏感配置：

```bash
config="$(mktemp --suffix=.yaml)"
cat > "$config" <<'YAML'
agentos:
  default-provider: primary
  providers:
    primary:
      type: openai
      base-url: ${AGENTOS_LLM_BASE_URL:}
      api-key: ${AGENTOS_LLM_API_KEY:}
YAML
```

```bash
workspace="$(mktemp -d "${TMPDIR:-/tmp}/agentos-cli.XXXXXX")"
java -jar AgentOS-boot/target/AgentOS.jar --config "$config" init \
  --workspace "$workspace" --model local-test-model
sha256sum "$workspace/agents/main/AGENT.md"
java -jar AgentOS-boot/target/AgentOS.jar --config "$config" init \
  --workspace "$workspace" --model another-model
sha256sum "$workspace/agents/main/AGENT.md"
```

预期：两次返回 0；第二次不覆盖已有 `main`；摘要列出新建/跳过项；两次 hash 相同；生成文件包含 `provider.name: primary` 和 `tools: []`。数据库、Provider 和网络不可用不影响本步骤。

## 3. Invalid initialization inputs

```bash
missing="$(mktemp -d "${TMPDIR:-/tmp}/agentos-cli-missing.XXXXXX")"
java -jar AgentOS-boot/target/AgentOS.jar --config "$config" init --workspace "$missing"; test "$?" -eq 2
find "$missing" -mindepth 1 -print
```

使用未知 Provider：

```bash
java -jar AgentOS-boot/target/AgentOS.jar --config "$config" init \
  --workspace "$missing/unknown" --model local-test-model --provider unknown-provider
 test "$?" -eq 2
 test ! -e "$missing/unknown"
```

预期：两种场景都在写入前失败，退出 2，不生成不完整工作区；Provider 名称只从 `--config` 指定文件的非敏感 `agentos.providers` key 投影校验；不读取环境变量或 `.env` 作为模型回退。显式配置文件不存在或损坏时也直接返回 2，不回退。

## 4. US2 stage demo: Help, version and single-message chat

使用项目现有本地模型替身和隔离数据库运行 `AgentOSCli.execute` 或对应 CLI 测试，先验证 `--help`、`--version` 和未知选项都不调用 launcher，再验证：

1. `--config <path> chat --workspace <root> --message "你好"` 输出回答到 stdout，成功返回 0；Boot 使用同一路径装配 Provider。
2. 不存在/损坏的 `--profile` 在启动上下文前返回 2，模型请求数为 0。
3. 空白 `--message` 返回 2，不创建 session/turn。
4. 运行时启动异常返回 1；轮次失败、持久化未确认或会话忙返回 3。
5. 交互输入 `消息\n\n失败后可继续\n/exit\n`：空行只写 stderr；可恢复轮次失败后仍可输入；正常 `/exit` 最终返回 0。

推荐复用和扩展：

- `AgentOS-cli/src/test/java/com/agentos/cli/AgentOSCliTest.java`
- `AgentOS-cli/src/test/java/com/agentos/cli/InitCommandBehaviorTest.java`
- `AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java`

## 5. Initialization performance and acceptance samples

在固定验收环境预热 3 次后，使用 20 个独立临时目录执行初始化，记录 p50/p95/最大耗时；至少 19 次必须在 2 秒内完成。重复初始化再执行 20 次，文件覆盖次数必须为 0。再用未知 Provider 执行 20 次，20/20 返回 2 且目标目录零写入。

对同一 OS 账号和 Agent 执行 20 次“消息→关闭→重启→消息”循环，20/20 次恢复历史；执行至少 20 对账号或 Agent 隔离用例，串线次数必须为 0。对空闲 `--new` 执行 20 次，20/20 次保留旧历史并创建新活跃会话；对忙会话执行 20 次，20/20 次返回 3 且零新增。

## 6. US3 and US4 stage demos: Identity, history and new session

### US3: interactive recovery demo

使用注入的 OS user supplier 或隔离 JVM 用户属性验证：

1. 相同 `(cli, OS account, Agent)` 连续两轮复用同一活跃会话。
2. 更换 OS 账号或 Agent 后历史完全隔离；v1 没有 `--user` 覆盖入口。
3. 关闭并重启 chat 后，PostgreSQL 中可恢复之前的消息、轮次和审计记录。

### US4: new-session safety demo

1. `--new` 对空闲会话归档并创建新活跃会话，旧记录仍可查询。
2. `--new` 遇到有效租约/运行中轮次返回 3，不新增轮次、不改写旧会话。

推荐复用：`AgentOS-boot/src/test/java/com/agentos/boot/DefaultAgentCliIntegrationTest.java`、`SessionConcurrencyIntegrationTest.java`，以及 `AgentOS-cli/src/test/java/com/agentos/cli/ChatCommandTest.java`。

## 7. Traceability

| Spec area | Validation |
|---|---|
| FR-001–FR-006, FR-019 | offline init, help/version, invalid path/model tests |
| FR-007–FR-013, FR-018, FR-020–FR-022 | `ChatCommandTest`, `AgentOSCliTest`, local model substitute |
| FR-014–FR-017 | PostgreSQL session/history and concurrency tests |
| FR-023–FR-024 | generated `primary`, unknown Provider zero-write, config path precedence and Boot/CLI shared config tests |
| SC-001–SC-004 | offline help/version/init and invalid input matrix |
| SC-002 | 20-run initialization timing and zero-overwrite matrix |
| SC-005–SC-006 | 20-run restart, identity isolation and `--new` matrix |
| SC-007–SC-008 | exit-code and interactive stdin matrix |

## 8. 本次实现执行记录

- C1：`AgentOSCliTest`、`ChatCommandTest`、`SpringContextLauncherTest` 已通过；help/version 与 init 未启动 launcher，未知选项/配置错误返回 2。
- C2：`scripts/cli_init_acceptance.py --runs 20 --warmup 3` 通过：独立初始化 20/20、重复初始化 20/20 且 `content_changes=0`、未知 Provider 20/20 返回 2 且 `writes=0`；20/20 次小于 2 秒，p50 约 0.342 秒、p95 约 0.371 秒、最大约 0.517 秒。
- C3：`scripts/cli_session_acceptance.py --runs 20` 通过：Maven 退出 0，CLI 行为报告 30/30，真实 CLI/PG 验收报告 3/3，无失败/错误/跳过；跨上下文恢复 20/20、身份或 Agent 隔离 20/20 且串线 0、真实 CLI 空闲 `--new` 20/20、真实 CLI 忙会话 `--new` 20/20。测试使用 `pgvector/pgvector:pg17` 和本地模型替身，未发送真实模型请求。

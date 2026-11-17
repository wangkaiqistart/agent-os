# Research: AgentOS CLI 入口与会话

**Feature**: [spec.md](spec.md)
**Date**: 2026-11-17

## R1. 命令入口与运行时边界

- **Decision**: 继续使用 `AgentOS-cli` 的 Picocli 命令树；`init`、帮助和版本直接执行本地文件/静态逻辑，`chat` 只有在工作区和 Agent 预校验通过后才通过 `ContextLauncher` 启动 Spring 上下文。
- **Rationale**: 当前 `AgentOSCli`、`InitCommand`、`ChatCommand` 和 `SpringContextLauncher` 已形成清晰的入口端口；轻命令不应因数据库、Provider 或网络不可用而失败，聊天则需要统一运行时服务。
- **Alternatives considered**: 在根入口一开始启动 Spring 会增加帮助、版本和初始化成本，并违反轻命令离线要求；在 CLI 内直接调用模型或数据库会绕过统一 AgentService、会话租约和审计。
- **Evidence**: `AgentOS-cli/src/main/java/com/agentos/cli/AgentOSCli.java`、`ContextLauncher.java`、`AgentOS-boot/src/main/java/com/agentos/boot/SpringContextLauncher.java`。

## R2. 工作区初始化与模型配置来源

- **Decision**: 初始化只接受显式 `--model`；未提供即返回用法/配置错误，不读取环境变量或执行目录 `.env`。未指定 Provider 时生成的默认 `main` Agent 显式记录 `provider.name: primary`；显式 Provider 不存在时在写入前失败。Provider 存在性只依据与运行时相同配置数据集的 `agentos.providers` map key 非敏感投影校验：CLI 通过全局 `--config` 接受显式配置文件，`ProviderNameProjectionLoader` 只提取 Provider 名称，不绑定或读取 `type`、URL、凭据等值；CLI 通过 `LocalProviderNameCatalog` 消费该投影。该路径不复用运行时 `ProviderCatalog`，不启动 Spring、不连接数据库、不访问网络。
- **Source precedence**: 显式 `--config` 路径最高优先级，且不存在/不可读/格式错误时不回退；未显式提供时依次查找当前目录 `application.yaml`、当前目录 `config/application.yaml`、`$XDG_CONFIG_HOME/agentos/application.yaml`、`~/.config/agentos/application.yaml`，最后查找可执行 JAR 的 classpath `application.yaml`。未找到配置时返回退出码 2。Boot chat 将同一路径通过 `spring.config.additional-location` 传给 Spring，保证 CLI 名称投影与运行时配置来源一致。
- **Rationale**: 这是本次规格澄清后的权威行为，能让初始化结果由命令输入决定，避免隐式读取开发机配置，也避免生成没有明确连接目标的 Agent。凭据仍不落盘，初始化不验证外部服务。
- **Alternatives considered**: 旧 `002-react-loop` 契约的 `--model -> AGENTOS_LLM_MODEL -> .env` 回退和省略 Provider 不写键，已被本功能用户澄清覆盖，不能直接复用。自动探测 Provider 或复制连接配置会引入隐式行为与敏感数据风险。
- **Implementation impact**: 调整 `InitModelResolver` 的输入策略，增加 `--config` 解析和 `ProviderNameProjectionLoader`、`LocalProviderNameCatalog` 的本地非敏感配置投影，调整 `WorkspaceInitializer` 默认 frontmatter，扩展 `ContextLauncher` 传递同一路径给 Boot，补充来源优先级/解析失败/无网络的单测和 `specs/002-react-loop/contracts/{workspace-and-cli,configuration}.md` 的同步说明；不修改 constitution。

## R3. Agent 选择与工作区一致性

- **Decision**: `chat` 使用同一个规范化绝对 workspace root 完成 Agent 候选扫描、选中 Agent 校验、Bootstrap 加载和 Spring 启动；选择顺序为显式 `--profile`、`main`、唯一旧工作区 Agent。显式选择或已存在的 `main` 损坏时不回退。
- **Rationale**: 这样可防止“扫描了指定目录、实际加载默认目录”的安全和正确性问题，也保留现有旧工作区兼容行为。只校验最终选中 Agent，避免无关损坏配置阻塞有效选择。
- **Alternatives considered**: 扫描到多个 Agent 时自动选择排序第一项会产生不可预测的会话身份；过滤损坏的 `main` 后回退会掩盖配置错误。
- **Evidence**: `ChatCommand.selectProfile`、`AgentLoader`、`SpringContextLauncher.launch(Path)` 及现有 `ChatCommandTest`、`DefaultAgentCliIntegrationTest`。

## R4. 会话身份、新会话与退出码

- **Decision**: 会话键为 `(channel=cli, userId=current OS account, profileName)`；v1 不提供用户标识覆盖参数。`--new` 只归档空闲活跃会话，忙会话返回处理失败并保留原状态。退出码固定为 `0` 成功、`1` 启动失败、`2` 用法/配置错误、`3` 单条消息处理失败或会话忙；交互模式单轮失败即时报告并继续，用户正常 `/exit`/EOF 时返回 `0`。
- **Rationale**: OS 账号沿用当前 CLI 约定且不增加伪造身份入口；归档由 `SessionManager` 原子协调，旧消息和审计保留。固定数字码适合脚本，交互模式则将可恢复轮次错误和进程生命周期分开表达。
- **Alternatives considered**: 允许 `--user` 会导致本地命令伪造其他会话；任一交互失败都返回 3 会让用户无法继续恢复；删除旧会话会破坏审计与历史。
- **Evidence**: `ChatCommand`、`SessionManager`、`SessionKey`、`specs/002-react-loop/contracts/workspace-and-cli.md` 的会话/退出码段落。

## R5. 运行时持久化与数据边界

- **Decision**: 不新增数据库表或第二套会话实现。CLI 只构造 `ProcessRequest` 并调用统一 `AgentService`; 会话、轮次、消息、模型/工具审计继续由 core/storage 现有 PostgreSQL + Flyway 链路保存。工作区文件由本地文件系统保存。
- **Rationale**: constitution 要求 CLI、REST、定时入口共用同一条处理链路，并要求审计 day one 落库；重复在 CLI 维护历史会造成串线和事务边界错误。
- **Alternatives considered**: 在 CLI 维护本地 JSON 历史无法支持跨入口一致性、租约和审计；新增 CLI 专用表会重复现有存储模型。
- **Evidence**: `AgentOS-core` 的 `AgentService`/`SessionManager` 契约、`AgentOS-storage` session/turn 存储、PostgreSQL 测试。

## R6. 验证策略与质量门

- **Decision**: 复用并扩展现有 CLI 单测、boot 端到端测试和并发集成测试；文件层使用临时目录与注入依赖，覆盖 `--config` 优先级和默认搜索顺序；装配层使用 Testcontainers `pgvector/pgvector:pg17` 与本地模型替身，并确认 Boot 使用与 CLI 相同的配置路径；最终运行 `mvn -B -ntp verify` 和 `python3 scripts/check_db_test_reports.py`。
- **Rationale**: 已有测试入口覆盖大多数行为，新增断言应集中在本功能澄清差异：显式 model-only、`primary` 写入、OS 账号身份、固定退出码和交互失败后最终 0。数据库和真实模型分别控制，普通质量门不发送真实请求。
- **Alternatives considered**: 仅测试 Picocli 输出无法证明 workspace 贯穿和跨重启历史；使用 H2/SQLite 违反项目宪章；把真实模型调用纳入普通 verify 会增加不确定外部依赖。
- **Evidence**: `AgentOS-cli/src/test/java/com/agentos/cli/*`、`AgentOS-boot/src/test/java/com/agentos/boot/DefaultAgentCliIntegrationTest.java`、`SessionConcurrencyIntegrationTest.java`。

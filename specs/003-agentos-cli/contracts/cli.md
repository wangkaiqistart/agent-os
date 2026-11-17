# CLI Contract: 命令、输出与退出码

**Owner**: `AgentOS-cli`；运行时实现：`AgentOS-core` / `AgentOS-storage` / `AgentOS-boot`
**Spec**: [../spec.md](../spec.md)

## Command grammar

```text
AgentOS --help
AgentOS --version
AgentOS [--config <path>] init [--workspace <path>] --model <model> [--provider <name>]
AgentOS [--config <path>] chat [--workspace <path>] [--profile <name>] [--message <text>] [--new]
```

`init` 的 `--model` 是必需业务输入。未提供、空白或不合法时返回 2，且不得创建工作区文件。未指定 `--provider` 时生成的默认 Agent 使用 `primary`；显式指定未知 Provider 返回 2，不生成引用它的 Agent。名称校验由 `ProviderNameProjectionLoader` 从最终选定的配置文件只提取 `agentos.providers` map 的 key，再由 `LocalProviderNameCatalog` 查询；不复用运行时 `ProviderCatalog`，不读取 URL、凭据或 `.env`，不启动 Spring、不连接数据库、不访问网络。名称目录缺失、格式错误或不可读取时返回 2。

`--config` 是全局配置文件选项，`init` 和 `chat` 使用同一份配置。显式路径最高优先级；显式路径不存在、不可读或格式错误时直接返回 2，不回退。未显式提供时按以下顺序查找：当前目录 `application.yaml`、当前目录 `config/application.yaml`、`$XDG_CONFIG_HOME/agentos/application.yaml`、`~/.config/agentos/application.yaml`，最后才尝试可执行 JAR 自带的 classpath `application.yaml`。未找到任何配置时返回 2，不使用硬编码 Provider 集合。

配置文件格式与 Boot 共用 `agentos.providers.<name>`；CLI 只提取名称 key，Boot 通过 `spring.config.additional-location` 加载同一文件完成运行时绑定。名称来源的优先级和加载失败必须可在单元测试中观察，不读取 `.env` 中的模型回退值。

`--workspace` 默认是执行目录下的 `.AgentOS`，相对路径相对于执行目录解析；一次命令中初始化、Agent 扫描、Bootstrap 加载和运行时启动使用同一个规范化绝对路径。初始化不启动 Spring、不连接数据库、不访问网络。

## Chat behavior

- 显式 `--profile` 优先；省略时选择 `main`，没有 `main` 时仅有一个 Agent 才兼容选择；多个 Agent 要求显式选择。
- 选中 Agent 必须在启动运行时之前通过 `AgentLoader` 校验；无效选择返回 2，不回退、不调用模型。
- `--message` 只提交一条消息并退出；省略时逐行读取输入，空行只提示错误，`/exit` 或 EOF 正常结束。
- `--new` 在运行时上下文中先归档该 CLI 身份下的空闲活跃会话；忙会话拒绝并返回 3，旧消息、轮次和审计不删除。
- 会话键固定为 `(cli, 当前操作系统账号, 选中的 Agent)`；v1 不允许覆盖账号标识。

## Exit codes

| Code | Meaning |
|---:|---|
| 0 | 成功；单条消息要求轮次成功且持久化已确认；交互模式表示用户正常结束且运行时仍可继续 |
| 1 | 运行时启动失败或本地文件/路径 I/O 失败 |
| 2 | 命令用法、Agent 配置、模型输入或 Provider 配置错误 |
| 3 | 单条消息处理失败、持久化未确认、会话忙或交互运行中断 |

交互模式中的可恢复轮次失败必须立即写入错误流并继续读取；用户随后正常 `/exit`/EOF 时最终返回 0。

## Output and safety

- 成功回答和初始化摘要写 stdout。
- 参数、配置、启动、轮次失败与持久化未确认提示写 stderr。
- 输出不得包含 API key、数据库密码、`.env` 原文、完整 prompt、工具参数或异常堆栈。
- 持久化未确认时必须显示安全提示和 `turnId`，不得把回答标记为已确认成功。

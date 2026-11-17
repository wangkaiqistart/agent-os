# Contract: 配置与时间预算

各模块使用配置属性；正式入口配置在 `AgentOS-boot/src/main/resources/application.yaml`，密钥仍只由已有环境变量注入。

**Phase 4 实施状态**：HTTP GET、白名单、治理回调、安全拒绝 resolver 和输出通知边界已实现；证据见 quickstart 第 10 节。下文审计退避、有界数据库 I/O 和恢复语义仍是 Phase 7 及后续目标，不能因配置示例存在就认为全部完成。

**2026-11-16 增量说明**：初始化模型输入与 CLI 工作区契约已按 Phase 10 实施并实测通过，证据见 quickstart 第 9.5 节；此结论不涵盖其他未完成阶段的时间预算、工具或会话归档设计。

```yaml
agentos:
  workspace:
    root: .AgentOS
  turn:
    storage-timeout: 5s              # 普通起步/续租/查证/正常收尾上限，仍扣同一整轮预算
    lease-ttl: 150s                  # 最短TTL，不是逻辑调用最长时限
    lease-grace: 30s                 # 租约至少覆盖剩余整轮预算再加这个余量
    cleanup: 1s              # deadline后所有收尾共享的总预算
  audit:
    max-retries: 2                 # 最多3次尝试，不保证预算内一定重试满
    backoff-first: 200ms
    backoff-second: 500ms
    attempt-timeout: 2s           # 每次审计数据库操作上限，还须取剩余预算的较小值
  tools:
    default-timeout: 30s             # 规划项：当前内置 ToolPolicy 固定为 30s，尚非绑定的全局开关
    default-max-result-chars: 10000   # 规划项：当前内置 ToolPolicy 固定为 10000，尚非绑定的全局开关
    http:
      allowed-domains: []
      user-agent: AgentOS/0.1
      max-redirects: 3               # 未配置为 3，显式 0 禁止跳转
      max-response-bytes: 1048576    # 沿用已有 HttpToolProperties 实际键名，最多 1 MiB
      timeout: 30s                   # 连接、跳转、body 共用；还受工具策略和整轮预算压短
spring:
  ai:
    tools:
      resolution:
        fallback:
          enabled: true             # 只能与只返回安全错误的解析器一起启用
```

`spring.ai.tools.limits.*`、`open-in-view=false`、`ddl-auto=validate`保持现有基线。

## 共享预算

- `deadline` 是本轮外部工作的截止时间；`hardStop=deadline+1s` 是从开始轮次到返回TurnResult的硬等待终点，包含在途调用的最终审计、工具收尾、轮次终态写入和释放租约。1秒由这些操作共同使用，不是每项各1秒。
- 正常执行阶段的模型、工具和审计取自身上限与剩余执行预算的较小值。deadline后禁止新模型请求、新工具执行、工具begin记录及普通审计退避；但deadline之前已经开始、此时才结束的调用，可以在hardStop前写入最后一条模型审计或更新原工具记录（例如timeout），不因执行预算归零直接拒写。
- 清理阶段的最终审计每项最多一次数据库写入尝试；提交结果未知时仅在同一剩余清理预算内按稳定键查询确认，不重放写入、不再等待200/500ms。其后finishAndRelease继续使用相同hardStop，不能另起1秒。执行超时与持久化确认分开：正常超时、最终审计和收尾均确认时返回TIMED_OUT + CONFIRMED；任何必要审计或收尾无法确认时返回对应的安全失败/超时结果 + UNCONFIRMED。
- `TurnExecutionBudget` 用单调时钟推进，由TurnRunner、TurnGuard、工具、审计记录器共享；`TurnBudgetRegistry`按turnId显式查找，不从线程或MDC查业务上下文。
- 外层有界等待负责保证调用方按时返回；到hardStop取消仍在运行的受管任务：必要持久化尚未确认才返回UNCONFIRMED；若仅终结通知尚未返回且持久化已确认，则保留已确认结果，不把通知超时变成持久化失败。底层JDBC连接/语句/锁等待、HTTP连接与body读取也必须有界，取消线程不能代替I/O超时。到期后迟到的任务不能继续外呼或追加消息，数据库写入仍须owner/状态条件保护。
- 审计每次尝试默认2秒，可配置1ms～5秒；普通审计取剩余执行预算，已在途调用的最终审计/查证取同一hardStop的剩余预算；间隔200/500ms仅在deadline前且剩余执行预算足够时等待。0.7秒只是两次等待之和，**不是审计重试总时长**。SQL通过事务内 `SET LOCAL statement_timeout/lock_timeout` 下发毫秒级剩余上限，连接获取等待也受外层预算约束；JDBC驱动socket/network timeout须配置，测试确认取消后连接不会被带着未完成事务复用。

## 本地与数据库的时间换算

- PostgreSQL的now()/CURRENT_TIMESTAMP固定为事务开始时间，不能用于本设计的“等待行锁后的实际当前时刻”。租约、deadline、接管和消息追加的有效性判断，在取得所需行锁之后取一次clock_timestamp()为db_now，本次操作统一使用它；下一次操作重新取值。普通created_at等记录时间可继续使用now()。

- 在调用startTurn之前创建单调时钟预算；起步SQL使用取得连接及所需行锁之后的剩余时长，数据库通过 `clock_timestamp() + :remaining_duration` 计算并返回deadline_at，不直接把Java墙钟时间当数据库时间。
- 起步事务的连接获取、执行和网络往返都消耗本地剩余预算。返回后的本地deadline不重新计时、不扩大；若已耗尽就不调用模型，直接在共享清理预算内收尾。
- ProviderRequest需要Instant deadline时，从本地当前Instant加当前单调剩余时长生成；它是向Provider传递剩余时限的适配值，不用于数据库租约比较。
- 数据库续租和消息追加使用数据库clock_timestamp()、数据库deadline_at及owner条件；本地在发起这些操作前仍检查单调剩余预算。两项条件都必须满足，任意一方已截止都拒绝追加；数据库时间条件不能放宽本地hardStop。测试模拟应用与数据库墙钟偏差，并覆盖取得连接/锁和网络往返已经消耗预算的情况。

## 租约

取得和续租时在行锁取得后捕获db_now=clock_timestamp()：`lease_until = db_now + max(lease-ttl, 本轮剩余执行预算 + lease-grace)`。因此默认300秒轮次初始租约至少330秒，覆盖Provider内部的等待与重试，无需修改Provider实现。150秒只是最短值。续租必须owner匹配、原租约未过期，已过期owner不能自行复活。

不新增后台心跳；代价是强杀后的回收等待可能达到整轮剩余时间加30秒。启动收尾不回收仍有效的租约，过期后下一条消息接管时原子回收旧轮次。

## 合法值与启动校验

| 配置/输入 | 规则 |
|-----------|------|
| lease-ttl、lease-grace、工具timeout | 必须为正Duration；按上述动态公式覆盖逻辑调用，不再按一次Provider timeout做WARN判定 |
| cleanup | 固定1秒，与SC-006一致，不提供扩大容差的配置 |
| turn.storage-timeout | 正Duration，默认5秒；普通轮次存储操作上限，不是额外整轮时长，也不扩大审计尝试或清理窗口 |
| audit.max-retries | 0到2；超过2拒绝，避免无限重试 |
| audit.backoff-first / audit.backoff-second | 必须为正Duration，默认200/500ms；总和不能替代预算判断 |
| audit.attempt-timeout | 1ms～5s，默认2s；包含调度、连接获取、SQL及提交，仍扣原预算 |
| HTTP max-redirects | 0到3；超过3拒绝 |
| HTTP max-response-bytes | 1～1048576 字节；0 归一化为缺省值 1048576 |
| default-max-result-chars | 规划中的全局策略配置；当前内置策略固定 10000，非已绑定开关 |
| allowed-domains | 绑定时去空格/空项，得到空列表即全部拒绝；有效条目不允许协议、端口、路径，支持精确域名与通配子域 |
| Agent max_iterations / turn_timeout | max_iterations整数≥1；turn_timeout正Duration |
| Agent max_history_turns / max_history_tokens | 整数≥0；任一为0表示不带历史；正token预算按90%使用 |
| AGENTOS_DB_URL缺失/不可达 | chat在模型请求前安全报错，包含变量名但不包含连接串/密码；init不启动上下文，不受影响 |

工作区缺agents目录时提示init；Provider未知/不可用在轮次开始前校验。核心通过新的只读 `ProviderCatalog.validate(Profile)` 端口调用provider实现的现有校验，不靠反射或调用模型试探；不改Provider的调用/重试逻辑。

## 初始化模型输入与工作区（FR-039/FR-040）

| 输入/配置 | 规则 |
|-----------|------|
| `init --model` | 创建缺失 main 时最高优先级；缺少参数值由命令解析报 2；提供空白/未解析占位符报 2，不回退 |
| 进程 `AGENTOS_LLM_MODEL` | 仅在未提供 `--model` 时读取；存在但无效即报 2，不回退 |
| 执行目录 `.env` 的 `AGENTOS_LLM_MODEL` | 仅在前两项未提供时读取；文件不存在/目标项不存在视为缺失；缺失、空白或未解析占位符报 2 |
| `.env` 格式 | UTF-8 `.properties` 语法，JDK Reader 解析，重复键最后一个生效，支持标准转义/续行；不执行 shell、不把引号当 shell 引号、不展开 `${...}`、不支持 `export` 前缀 |
| `.env` 读取/解析失败 | 文件 I/O 错误为 1，非法配置语法为 2；只说明路径/配置项和安全原因，不返回原文或原始异常文本，不回退到其他模型 |
| 已有合法 main | 不读取新的模型来源，不用参数更新文件；旧配置保持不变。命令语法错误仍由 Picocli 拒绝 |
| `init --provider` | 可选非空白连接名，只在创建 main 时写 provider.name；省略不写，聊天沿用 `agentos.default-provider`；不把 primary 固定进默认模板 |
| `--workspace` | init/chat 默认 `.AgentOS`；相对执行目录解析，指向工作区根本身；命令入口规范化为绝对 Path |
| `agentos.workspace.root` | chat 将上述 Path 作为 Spring 命令行配置源传入，优先于普通配置文件和进程环境中的同名值；不使用 JVM 全局属性传递 |

所有候选值均须是具体非空白模型名；初始化不校验服务端模型目录。值不做 shell 去引号或环境插值，输入原值不进入错误文案。高优先级值已提供时，不再访问低优先级来源；`.env` 位置始终是命令执行目录，不跟随自定义 workspace 移动。上述模型环境变量只作为初始化输入，不是 Provider 连接中的默认模型；之后修改环境变量不会自动改写现有 Agent。

`.env` 的解析边界是仅投影模型名，其他键不进入初始化参数或 Spring Environment，不写入生成文件、不输出。初始化不启动 Spring、不装配 Provider/DataSource、不联网；若缺少模型名，必须在任何工作区写入前返回 2。测试使用合成配置文件和可注入环境查询函数，禁止读取真实开发 `.env` 或依赖开发机模型变量。

工作区经 `ContextLauncher.launch(Path)` 到达 boot，以 `.run(...)` 的 `--agentos.workspace.root=<path>` 单个参数绑定；core 的 WorkspaceProperties 继续同时构造 AgentLoader 与 ContextLoader。自动化装配测试可注入隔离的 Environment：测试配置源低于该 commandLineArgs、高于普通环境/配置文件，并关闭 `.env` 导入；生产入口不增加测试选项。不能只验证扫描路径或把 builder 默认属性误当高优先级覆盖。

## 输出回调的硬截止约束

过程回调运行在有界的轮次任务内，终结通知由AgentService在必要持久化处理结束后决定，单独提交到受管虚拟线程并只等待同一个hardStop的剩余时长，不为回调新增超时窗口。若之前的过程回调仍阻塞、sink已失效或hardStop已到，则不再调用sink，记录安全的通知未送达日志并直接返回TurnResult。

终结回调最多尝试一次。onComplete/onError抛异常或阻塞超时，仅表示通知失败；不能回滚或改写已确认的业务终态和persistenceStatus，不能再补发另一种终结事件。取消回调Future并清理受管任务引用；不承诺强行终止不响应中断的用户回调，但必须禁止其触发后续系统事件、外呼或消息写入。验收“按时返回”不以任意用户回调必然响应中断为前提。

## 远程数据库审计修复（2026-11-17）

250ms固定上限已由默认2秒、可配置1ms～5秒替代。此修正来自实际提交与查证读超时，不改变FR-019/SC-006的deadline+1秒硬等待终点；最终审计可以跨越业务deadline，但所有收尾仍共用同一hardStop。超时只结束审计调用方等待，不interrupt数据库事务线程。数据库线程独立按SQL/socket超时结束，在事务入口、配置往返后及提交前检查原操作时限；连接设置恢复失败时丢弃连接，不能交回池复用。提交未知仍只查原唯一键和完整载荷，查不到不能宣称回滚或重新写入。

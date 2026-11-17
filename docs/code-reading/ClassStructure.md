# AgentOS 当前类结构

这份文件只记录源码中已经存在的主要类，核对至2026-11-17的US-2完整验收（`3228b9d`）。图中的调用箭头是协作关系，不表示跨Bean共用一个数据库事务。

## 关系含义

```text
继承：       子类 --|> 父类
实现接口：   实现类 ..|> 接口
使用依赖：   A --> B
组合关系：   A *-- B
```

## 总体结构

```mermaid
flowchart TD
    Boot[AgentOSApplication] --> CLI[AgentOSCli]
    CLI --> Init[InitCommand]
    Init --> Workspace[WorkspaceInitializer]
    Workspace --> Resolver[InitModelResolver]
    Resolver --> Options[DefaultAgentOptions]
    Workspace --> Main[agents/main/AGENT.md]
    CLI --> Chat[ChatCommand]
    Chat -->|同一规范化工作区 Path| Launcher[SpringContextLauncher]
    Launcher --> CoreAuto[CoreAutoConfiguration]
    Launcher --> ProviderAuto[ProviderAutoConfiguration]
    Launcher --> StorageAuto[StorageAutoConfiguration]
    Launcher --> ToolAuto[ToolAutoConfiguration]
    ToolAuto --> Registry[ToolRegistry / GovernedToolCallback]
    ToolAuto --> Official[官方 ToolCallingAutoConfiguration]
    CoreAuto --> Service[DefaultAgentService]
    ProviderAuto --> Provider[DefaultProviderService]
    ProviderAuto --> Catalog[DefaultProviderCatalog]
    StorageAuto --> Turn[JpaTurnStore]
    StorageAuto --> Messages[JpaMessageStore]
    StorageAuto --> Sessions[JpaSessionManager]
    StorageAuto --> Audit[JpaLlmCallRecorder]
    Service --> Loop[ReActLoop]
    Loop --> Provider
    Loop --> Executor[ToolExecutor]
    Executor --> Manager[注入的 ToolCallingManager]
    Manager --> Registry
    Service --> Turn
    Loop --> Messages
    Service --> Catalog
```

依赖方向是：入口依赖 Core，Core 只依赖端口，Provider 和 Storage 实现端口，Boot 通过 Spring 自动配置把它们放在一起。Core 不应该反向依赖 JPA 或具体模型 SDK。

## 一次请求的对象关系

```mermaid
classDiagram
    class AgentService {
        <<interface>>
        +process(ProcessRequest) TurnResult
        +process(ProcessRequest, TurnSink) TurnResult
    }
    class DefaultAgentService {
        -AgentLoader loader
        -ProviderCatalog providers
        -SessionManager sessions
        -TurnStore turns
        -ReActLoop loop
        +process(...)
    }
    class ReActLoop {
        +run(TurnSnapshot, LoadedAgent, String, TurnExecutionBudget, TurnSink) Result
    }
    class ProviderService {
        <<interface>>
        +call(ProviderRequest) ProviderResult
        +stream(ProviderRequest, Consumer) ProviderResult
    }
    class TurnStore {
        <<interface>>
        +startTurn(...)
        +renewLease(...)
        +finishAndRelease(...)
    }
    class MessageStore {
        <<interface>>
        +append(...)
        +recentTurns(...)
    }
    class DefaultProviderService
    class JpaTurnStore
    class JpaMessageStore

    DefaultAgentService ..|> AgentService
    DefaultAgentService --> ReActLoop
    DefaultAgentService --> TurnStore
    DefaultAgentService --> SessionManager
    DefaultAgentService --> ProviderCatalog
    DefaultAgentService --> TurnRunner
    DefaultAgentService --> TurnBudgetRegistry
    ReActLoop --> ProviderService
    ReActLoop --> MessageStore
    ReActLoop --> ToolExecutor
    ToolExecutor --> ToolCallingManager
    DefaultProviderService ..|> ProviderService
    JpaTurnStore ..|> TurnStore
    JpaMessageStore ..|> MessageStore
```

## Core 中的三条关键线

```text
AgentProfile / AgentSettings
  -> LoadedAgent
  -> PromptBuilder
  -> ProviderRequest
```

```text
SessionKey
  -> TurnStore.startTurn
  -> TurnSnapshot
  -> TurnGuard.checkpoint -> TurnStore.renewLease
  -> TurnStore.finishAndRelease
```

起步由TurnStore原子查找/创建active会话，不在服务层先查会话再创建轮次；CLI的`--new`另经SessionManager归档空闲会话。

```text
ProviderRequest
  -> ProviderService
  -> ProviderResult / ProviderCallException
  -> LlmCallRecord
  -> LlmCallRecorder
```

## Storage 的实体关系

```mermaid
erDiagram
    sessions ||--o{ agent_turns : contains
    agent_turns ||--o{ session_messages : contains
    agent_turns ||--o{ llm_calls : audits
    agent_turns ||--o{ tool_invocations : audits
```

`SessionEntity` 保存租约；`AgentTurnEntity` 保存轮次状态；`SessionMessageEntity` 保存用户、助手、工具消息及结构化请求/结果；`LlmCallEntity` 保存一次模型尝试的结构化审计；`ToolInvocationEntity` 保存执行意图及终态。Core 只拿 `SessionSnapshot`、`TurnSnapshot` 和 `HistoryTurn`，不拿 JPA 实体。

## 入口模块

```mermaid
flowchart LR
    I[stdin] --> CC[CliChannel]
    CC --> Request[ProcessRequest]
    Chat[ChatCommand] --> Request
    Request --> AS[AgentService]
    AS --> Result[TurnResult]
    Result --> Output[CLI 输出]
```

`CliIdentity` 把 `cli + 登录用户名 + Agent 名` 组成 `SessionKey`。`CliChannel` 只处理输入、`/exit`、空行和输出，不参与模型调用。

## 默认主 Agent 初始化与加载

`InitModelResolver` 只在 main 缺失时生成 `DefaultAgentOptions(model, providerName)`；WorkspaceInitializer 用 SnakeYAML 写普通 AGENT.md。根部 AGENTS.md 是共享指令，不是 Profile。main 没有独立引擎或数据库表。

```mermaid
flowchart LR
    Init[InitCommand] --> Files[WorkspaceInitializer]
    Files --> Main[agents/main/AGENT.md]
    Chat[ChatCommand] --> Local[AgentLoader 本地校验]
    Local --> Port[ContextLauncher.launch Path]
    Port --> Boot[SpringContextLauncher]
    Boot --> Property[commandLineArgs workspace.root]
    Property --> Core[WorkspaceProperties.root]
    Core --> Loader[AgentLoader]
    Core --> Context[ContextLoader]
    Loader --> Main
    Context --> Shared[AGENTS / SOUL / USER]
```

选择规则是显式 Agent、main、旧工作区唯一 Agent。文件错误在启动器前失败；真实 Provider 校验仍在统一服务链路里。默认 main 无工具；Phase 4 已接通显式授权的 http_get 工具闭环，Phase 6 的 `--new` 会先归档空闲会话，历史恢复由 ReActLoop/MessageStore/PromptBuilder 完成。


## 工具对象关系

```mermaid
flowchart LR
    Service[DefaultAgentService] --> Loop[ReActLoop]
    Service --> Sink[ManagedTurnSink]
    Loop --> Catalog[ToolCatalog / ToolRegistry]
    Loop --> Executor[ToolExecutor]
    Executor --> Manager[官方 ToolCallingManager]
    Executor --> Ledger[ToolCallLedger]
    Manager --> Governed[GovernedToolCallback]
    Manager --> Unknown[UnknownToolResolver]
    Unknown --> Governed
    Governed --> Http[HttpTools]
    Http --> Sandbox[WhitelistSandbox]
    Governed --> Audit[ToolInvocationRecorder / JpaToolInvocationRecorder]
    Governed --> Redactor[Redactor]
    Audit --> Redactor
```

Provider 请求阶段只有 TurnContext；响应后才建 ToolCallLedger。全部工具共享同一 TurnExecutionBudget，callback 的参数通过 ToolContext 显式注入，不依赖日志 MDC。具体先后顺序和断点见 AgentOS-tool 导读。

## 审计、事务与启动恢复

```mermaid
flowchart LR
    Service[DefaultAgentService] --> Budget[TurnExecutionBudget / TurnBudgetRegistry]
    Llm[JpaLlmCallRecorder] --> Retry[RetryingAuditWriter]
    Tool[JpaToolInvocationRecorder] --> Retry
    Retry --> Tx[AuditTransactions: 独立短事务与 I/O 上限]
    Turn[JpaTurnStore / JpaMessageStore] --> Tx
    Launcher[chat launcher] --> Runner[TurnReaperRunner]
    Runner --> Reaper[core TurnReaper]
    Reaper --> Store[JpaTurnStore.recoverSession]
    Start[startTurn 接管] --> Store
```

审计重试不拥有外部回调，因此不会重发模型或工具。恢复逻辑依据数据库 owner/lease 和终态，既清理失效 RUNNING，也清理终态父轮次的孤儿工具。关闭时 DefaultAgentService 只尝试有限清理；强杀交给下一次启动或接管恢复。

`AuditTransactions` 名字含审计，但当前也承载普通轮次存储的程序式短事务。普通存储默认5秒，审计默认2秒；最终收尾共用deadline后的1秒。启动扫描另有30秒总预算，逐会话事务最多5秒，不借给单轮收尾使用。

## 并发验收的对象边界

```mermaid
flowchart LR
    A[入口实例 A] --> DB[同一 PostgreSQL session 行锁与唯一索引]
    B[入口实例 B] --> DB
    DB --> State[锁后检查 owner / lease / 数据库时间 / 父轮次]
    State --> Busy[忙: 零写入拒绝]
    State --> Recover[失效: 原子恢复后接管]
    State --> Write[有效 owner: 续租 / 追加 / 收尾]
```

租约跨越模型/工具等待，行锁只跨短事务。旧owner的迟到消息、工具begin和finish都必须重新校验；不能清理新owner租约。对应测试是storage的`JpaTurnStoreContractTest`与boot的`SessionConcurrencyIntegrationTest`。完整覆盖和真实天气证据见[Phase 9 验证记录](../testing/phase9-validation-2026-11-17.md)。

# Implementation Plan：工具交互式授权

**Spec**: [spec.md](./spec.md)

## 设计要点

1. **沙箱三态。** `WhitelistSandbox` 仍是唯一的 Sandbox 实现（constitution：Sandbox 接口先行）。判定结果为直接放行、`ApprovalRequiredException`（继承 `SandboxViolationException`，携带 `ApprovalSubject`）、硬拒绝。`SandboxAction` 增加中立的 `details` 列表，承载 cwd/参数、HTTP 方法、写入方式。
2. **授权网关。** `ToolApprovalGate`（AgentOS-tool）：先调沙箱；需要授权时查 `SessionApprovalGrants`（sessionId → grantKey，进程内，最多 1000 个会话），否则经本轮 `TurnRuntimeCapabilities` 的 `InteractionPort.approve(...)` 询问。write_file、edit_file、shell、http_get（每一跳）、http_post 改用网关；读取类工具仍直接调用沙箱。
3. **交互契约。** core 新增 `ApprovalRequest`、`ApprovalDecision`；`InteractionPort.approve` 默认返回 UNSUPPORTED，因此所有无人值守入口自动拒绝。
4. **暂停计时。** `TurnExecutionBudget.pause()` 冻结余量、关闭后平移 deadline 与 hard stop；`ToolExecutionScope.pauseClock()` 同时暂停工具与整轮。`TurnRunner` 与 `GovernedToolCallback.attempt` 改为分段等待并重读余量；`JpaTurnStore.renew` 在轮次暂停过时把 `deadline_at` 延到 `now + remaining`。
5. **CLI。** `TerminalInputBroker.approve` 复用单 owner 读取：展示摘要和编号选项，`1/y` 本次、`2` 本会话（不可用时不显示）、拒绝选项或空行拒绝、其他文字作为拒绝原因。
6. **文件能力。** `FileAccessPolicy` 的静态能力扩为"工作区 ∪ 配置根"，授权通过后加锁、提交前复核照常进行；`preApprovedWrite` 区分写根与工作区。
7. **解耦。** `ManagedStdioTransport` 只校验程序与 cwd 存在；`BraveSearchProvider`、`McpHttpTransportFactory` 对 `ApprovalRequiredException` 放行（部署者配置即授权）。

## 配置

```yaml
agentos:
  tools:
    approval:
      timeout: 10m          # 单次等待上限，等待期间整轮计时暂停
    shell:
      inherit-environment: [PATH, HOME, LANG, TMPDIR, USER]
```

白名单（`file.*-allowed-paths`、`shell.commands`、`http.allowed-domains`）含义改为"预先授权"，无人值守入口只能执行其中的动作。

## 验证

- 单元：`ToolApprovalGateTest`（三态矩阵、会话授权隔离、拒绝原因、无人值守、超时、等待暂停）、`TerminalInputBrokerApprovalTest`（选项解析、提示内容、超时后迟到行）、`TurnBudgetPauseTest`（冻结/平移/嵌套/取消、TurnRunner、scope 暂停）。
- 集成：`ToolApprovalIntegrationTest`（真实 Manager + PostgreSQL + CLI broker：写入确认、会话授权、拒绝原因回模型、shell/http 确认、无人值守零动作、2 秒预算下 3 秒等待仍成功）。
- 调整的既有测试只改前提（工作区内默认可读、部署者端点可信、默认继承基础变量），"拒绝时零动作"的断言保持。
- 质量门：`mvn -B -ntp verify`。

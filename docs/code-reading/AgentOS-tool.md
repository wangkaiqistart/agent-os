# AgentOS-tool 代码导读

当前新增的是 HTTP 工具配置契约，不是完整的 HTTP 工具执行器。执行器仍需要后续阶段接入统一的 ToolCatalog、Sandbox、账本、超时、截断和脱敏。

## `HttpToolProperties`

入口：[HttpToolProperties.java](../../AgentOS-tool/src/main/java/com/agentos/tool/http/HttpToolProperties.java)

它绑定 `agentos.tools.http`：

| 字段 | 默认值/边界 |
|---|---|
| `allowedDomains` | 空列表；自动去空格和空项 |
| `userAgent` | `AgentOS/0.1` |
| `maxRedirects` | 0 按默认值 3；最终范围 0～3 |
| `maxResponseBytes` | 0 按默认值 1 MiB；最终范围 1～1 MiB |
| `timeout` | 30 秒；必须为正数 |

```mermaid
flowchart LR
    YAML[application.yaml / 环境变量] --> Binding[@ConfigurationProperties]
    Binding --> Props[HttpToolProperties]
    Props -.后续.-> Governance[HTTP Tool 执行与治理]
```

这个值对象先把配置规范化并拒绝超范围值，避免真正执行 HTTP 请求时再猜默认值。当前没有 `HttpTool`、`ToolCallback` 或网络调用代码，因此不能把这批代码当作 HTTP 工具已经可用。

## 测试

`HttpToolPropertiesTest` 只验证默认值、空白域名过滤和边界校验，不发送网络请求。后续实现工具时，应从 Core 的 `ToolCatalog` 允许列表进入，并把每次调用交给 Storage 的 `tool_invocations` 账本。

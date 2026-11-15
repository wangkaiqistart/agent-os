# Contract: 配置

**Owner**: 各模块的 `@ConfigurationProperties` | **File**: `AgentOS-boot/src/main/resources/application.yaml`

本 story 新增以下配置。密钥仍然只来自环境变量，这里没有新的密钥项。

```yaml
agentos:
  workspace:
    root: .AgentOS                  # 工作区目录，相对启动目录
  turn:
    lease-ttl: 150s                 # 会话租约时长，要大于最长的单次模型调用加 30 秒
  audit:
    write-retries: 2                # 审计写入失败后的重试次数，共 3 次尝试
    retry-backoff: [200ms, 500ms]
  tools:
    default-timeout: 30s
    default-max-result-chars: 10000
    http:
      allowed-domains: []           # 空表示全部拒绝
      user-agent: AgentOS/0.1
      max-redirects: 3
      max-body-bytes: 1048576
spring:
  ai:
    tools:
      resolution:
        fallback:
          enabled: true             # 配合"只返回错误"的解析器，守住 Agent 的工具名单
```

`spring.ai.tools.limits.*`、`spring.jpa.open-in-view=false`、`spring.jpa.hibernate.ddl-auto=validate` 已经在 `application.yaml` 里，不改。

## 启动校验

| 条件 | 结果 | 错误信息需包含 |
|------|------|---------------|
| `turn.lease-ttl` 不是正数 | 启动失败 | 字段名、取值 |
| 某个 Provider 连接的 `timeout` 加 30 秒大于 `turn.lease-ttl` | 打 WARN，不阻止启动 | 连接名、两个值 |
| `audit.write-retries` 为负数 | 启动失败 | 字段名、取值 |
| `tools.http.max-redirects` 为负数，或 `max-body-bytes` 不是正数 | 启动失败 | 字段名、取值 |
| `tools.http.allowed-domains` 里有空条目或带协议、路径的条目 | 启动失败 | 条目原文（应当写成 `api.met.no` 或 `*.example.com`） |
| `AGENTOS_DB_URL` 没有设置 | `chat` 启动前报错，不发出任何模型请求；`init` 不受影响 | 变量名 |

`workspace.root` 下没有 `agents` 目录时，`chat` 提示先运行 `AgentOS init`，不报堆栈。

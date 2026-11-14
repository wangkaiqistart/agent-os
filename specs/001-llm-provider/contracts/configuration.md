# Contract: Provider 与向量化配置

**Owner**: `AgentOS-provider`（`ProviderProperties`、`EmbeddingProperties`） | **File**: `AgentOS-boot/src/main/resources/application.yaml`

## 目标形态

```yaml
agentos:
  default-provider: primary
  providers:
    primary:
      type: openai
      base-url: ${AGENTOS_LLM_BASE_URL:}         # 写到 /v1，例如 https://<中转>/v1；缺省时由启动校验给出明确错误
      api-key: ${AGENTOS_LLM_API_KEY:}           # 缺省为空，由启动校验给出明确错误
      timeout: 120s
      retry-min-remaining: 15s                   # 可省略，默认 15s；剩余时间少于它就不重试
      retry-max-wait: 120s                       # 无 deadline 时 Retry-After 等待上限，默认等于 timeout
    deepseek:
      type: deepseek
      base-url: ${AGENTOS_DEEPSEEK_BASE_URL:https://api.deepseek.com}
      api-key: ${AGENTOS_DEEPSEEK_API_KEY:}
      timeout: 60s
  embedding:
    enabled: ${AGENTOS_EMBEDDING_ENABLED:true}
    base-url: ${AGENTOS_EMBEDDING_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
    api-key: ${AGENTOS_EMBEDDING_API_KEY:}
    model: ${AGENTOS_EMBEDDING_MODEL:text-embedding-v4}
    dimensions: ${AGENTOS_EMBEDDING_DIMENSIONS:1024}
    max-batch-size: 10
    timeout: 30s
    startup-check: async                         # async（默认，后台校验）或 sync（启动时校验）
```

连接配置里没有 `model`：聊天模型由 Profile 提供（FR-001）。

## 启动校验

| 条件 | 结果 | 错误信息需包含 |
|------|------|---------------|
| `default-provider` 指向的连接不存在 | 启动失败 | 默认连接名 |
| 默认连接 `api-key` 为空 | 启动失败 | 连接名、环境变量名（从配置原文的 `${...}` 取） |
| 某连接 `type` 不是 `openai` / `deepseek` | 启动失败 | 连接名、类型、"暂不支持" |
| 某连接 `timeout` 不是正数 | 启动失败 | 连接名 |
| 某连接 `retry-min-remaining` 不是正数，或大于 `timeout` | 启动失败 | 连接名、两个值 |
| 某连接 `retry-max-wait` 不是正数，或小于 `retry-min-remaining` | 启动失败 | 连接名、两个值 |
| 某连接 `base-url` 为空 | 启动失败 | 连接名 |
| 非默认连接 `api-key` 为空 | 标记为不可用，启动继续，打一条 INFO | 连接名 |
| `embedding.enabled=false` 或 `api-key` 为空 | 向量化为 DISABLED，不创建模型 | — |
| `embedding.dimensions` 或 `max-batch-size` 不是正数 | 启动失败 | 字段名 |
| `embedding.startup-check` 不是 `async` / `sync` | 启动失败 | 字段名、取值 |
| 向量化校验请求返回的维度与配置不同（`async`） | 启动不受影响，向量化为 MISCONFIGURED，打 ERROR | 配置值、实际值 |
| 向量化校验请求返回的维度与配置不同（`sync`） | 启动失败 | 配置值、实际值 |
| 向量化校验请求失败 | 向量化为 UNAVAILABLE，打 WARN；除鉴权失败外在后台退避重新校验 | 不可达 / 鉴权失败 / 欠费 / 其他 |

`async` 模式下，向量化校验在应用就绪之后才开始，表中"启动"相关的结果不受向量化服务快慢影响（SC-020）。

连接名不区分大小写地唯一（YAML 的 map 键本身唯一，此项防止 `Primary` 和 `primary` 并存）。

## Spring AI 自动配置

保持 [application.yaml](../../../AgentOS-boot/src/main/resources/application.yaml) 现有设置：`spring.ai.model.chat/embedding/image/audio.*/moderation` 全部为 `none`。Provider 模块不注册任何 `ChatModel` / `EmbeddingModel` Bean，容器里仍有 `ToolCallingManager`、`ObservationRegistry`。

## 环境变量

| 变量 | 必需 | 说明 |
|------|------|------|
| `AGENTOS_LLM_BASE_URL` / `AGENTOS_LLM_API_KEY` | 是 | 默认连接 `primary`；base-url 写到 `/v1` |
| `AGENTOS_LLM_MODEL` | 测试用 | 冒烟测试构造 Profile 用；应用本身不读 |
| `AGENTOS_DEEPSEEK_BASE_URL` / `AGENTOS_DEEPSEEK_API_KEY` | 否 | key 为空时连接不可用 |
| `AGENTOS_DEEPSEEK_MODEL` | 测试用 | 同上 |
| `AGENTOS_EMBEDDING_*` | 否 | key 为空时向量化 DISABLED；base-url 写到 `/v1` |

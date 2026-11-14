---
title: 模型与配置
lang: zh-CN
description: AgentOS 的 Provider 显式映射、推理模型参数和独立 Embedding 配置。
---

# 模型与配置 {#models}

AgentOS 不靠扫描容器里的 `ChatModel` Bean 来猜模型。每个 Provider 有明确名称，Profile 通过名称选择；Provider 只配连接（类型、地址、凭证、超时），聊天模型和生成参数由 Profile 提供。Embedding 独立配置，不和主链路模型绑定。

## 主链路 Provider {#providers}

测试环境的 `primary` 是 OpenAI 兼容中转接口上的 `gpt-5.6-luna` 推理模型。地址、key 和模型名只从环境变量读取：

```properties
AGENTOS_LLM_BASE_URL=https://your-openai-compatible-endpoint/v1
AGENTOS_LLM_API_KEY=<your-key>
AGENTOS_LLM_MODEL=gpt-5.6-luna
```

`base-url` 要写到 `/v1`，代码不会自动补。主链路对推理模型有三个注意点，它们都由 Profile 设置，Profile 没写的参数请求里就不带：

- 不发送 `temperature`，避免提供方拒绝不适用的参数；
- 使用 `reasoning_effort` 控制推理强度；
- 使用 `max_completion_tokens` 设置输出预算（`deepseek` 类型改发 `max_tokens`，DeepSeek 会忽略前者）。

中转接口需要单独实测工具调用、流式工具调用、用量统计、限流响应头和 `json_schema` 结构化输出。文档中的 `useProviderStructuredOutput()` 在确认兼容前默认关闭。

## Provider 显式映射 {#mapping}

```yaml
agentos:
  default-provider: primary
  providers:
    primary:
      type: openai
      base-url: ${AGENTOS_LLM_BASE_URL}
      api-key: ${AGENTOS_LLM_API_KEY}
      timeout: 120s
      retry-min-remaining: 15s
      retry-max-wait: 120s
    deepseek:
      type: deepseek
      base-url: ${AGENTOS_DEEPSEEK_BASE_URL:https://api.deepseek.com}
      api-key: ${AGENTOS_DEEPSEEK_API_KEY:}
      timeout: 60s
```

`ProviderService` 启动时按配置逐个建立连接，以名称为键保存，不注册 `ChatModel` Bean。同一个 OpenAI 兼容协议可以配置多家服务，但每家必须拥有不同的 Provider 名称。

目前支持两种连接类型：`openai`（OpenAI 兼容）和 `deepseek`，两者用同一套实现，只在输出上限字段上不同。配置 `anthropic`、`ollama` 等其他类型会启动失败。默认连接的 key 为空也会启动失败；其他连接的 key 为空只标记为不可用。provider 名称未知、连接不可用或 Profile 缺少模型，都在发出网络请求之前失败。

## 重试与截止时间 {#retry}

只对 429、500 / 502 / 503 / 504 和临时连接错误重试，每次调用最多追加 1 次；鉴权、参数、未知模型错误不重试。等完 `Retry-After` 后本轮剩余时间少于 `retry-min-remaining` 就不重试，重试请求的超时取连接超时与剩余时间中较小的值。流式调用一旦向调用方交出过文本，就不再重试。每次实际发出的请求都会产生一条调用记录；目前写入结构化日志，落库属于 US-2。

## 图片输入 {#images}

用户消息可以附带图片（内嵌数据或网址），两种连接类型行为一致。音频、视频等非图片媒体会在发出请求之前失败。调用记录只保存图片数量，不保存图片数据和网址。

## Embedding 独立配置 {#embedding}

测试环境使用百炼 `text-embedding-v4`：

```properties
AGENTOS_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AGENTOS_EMBEDDING_API_KEY=<dashscope-key>
AGENTOS_EMBEDDING_MODEL=text-embedding-v4
AGENTOS_EMBEDDING_DIMENSIONS=1024
```

`base-url` 要写到 `/v1`，最终请求为 `<base-url>/embeddings`。Spring AI Starter 的 Embedding 自动配置关闭，应用只创建一个编程式 `OpenAiEmbeddingModel`；没有启用或 key 为空时一个都不创建，调用会得到“向量化未启用”。

向量维度是数据库表结构的一部分：Flyway 用配置占位符创建 `vector(1024)`。应用就绪后会在后台用一次真实请求校验返回维度，不阻塞启动；维度不一致标记为配置错误并拒绝向量化调用，服务不可达或欠费时按退避在后台重新校验。需要“维度错误就拒绝启动”时配置 `startup-check: sync`。服务单次最多 10 条，`EmbeddingService` 自己按条数分批。

## 密钥与环境 {#secrets}

- 根目录 `.env.example` 只有占位符；真实 `.env` 不提交；
- PostgreSQL、主链路模型、Embedding 的凭证互相独立；
- 占位符解析和必填校验的目标是由 `ConfigLoader` 负责；US-1 暂由 Spring 占位符加 Provider 自动配置完成，US-5 迁入；
- 日志、工具结果和审计字段都不能打印密钥。

## 辅助调用与评估 {#auxiliary}

生成 `AGENT.md` 草稿和评估器可以使用 `ChatClient`，但不传 tools，并关闭 `ToolCallingAdvisor` 自动注册。若配置独立的 DeepSeek Provider，可让评估器使用它，避免主链路模型“自己评自己”。

## 设计依据 {#sources}

- [技术方案第 3 章（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [技术方案第 8.8 节（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)

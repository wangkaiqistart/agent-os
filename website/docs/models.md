---
title: 模型与配置
lang: zh-CN
description: AgentOS 的 Provider 显式映射、推理模型参数和独立 Embedding 配置。
---

# 模型与配置 {#models}

AgentOS 不靠扫描容器里的 `ChatModel` Bean 来猜模型。每个 Provider 有明确名称，Profile 通过名称选择；Embedding 独立配置，不和主链路模型绑定。

## 主链路 Provider {#providers}

测试环境的 `primary` 是 OpenAI 兼容中转接口上的 `gpt-5.6-luna` 推理模型。地址、key 和模型名只从环境变量读取：

```properties
AGENTOS_LLM_BASE_URL=https://your-openai-compatible-endpoint
AGENTOS_LLM_API_KEY=<your-key>
AGENTOS_LLM_MODEL=gpt-5.6-luna
```

主链路对推理模型有三个注意点：

- 不发送 `temperature`，避免提供方拒绝不适用的参数；
- 使用 `reasoning_effort` 控制推理强度；
- 使用 `max_completion_tokens` 设置输出预算。

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
      model: ${AGENTOS_LLM_MODEL}
      reasoning-effort: low
      timeout: 120s
```

`ProviderService` 启动时按配置逐个构建 `ChatModel` 并放入 `Map<ProviderName, ChatModel>`。同一个 OpenAI 兼容协议可以配置多家服务，但每家必须拥有不同的 Provider 名称。

## Embedding 独立配置 {#embedding}

测试环境使用百炼 `text-embedding-v4`：

```properties
AGENTOS_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
AGENTOS_EMBEDDING_API_KEY=<dashscope-key>
AGENTOS_EMBEDDING_MODEL=text-embedding-v4
AGENTOS_EMBEDDING_DIMENSIONS=1024
```

`base-url` 不带 `/v1`，Spring AI OpenAI 模块会请求 `/v1/embeddings`。Spring AI Starter 的 Embedding 自动配置关闭，应用只创建一个编程式 `OpenAiEmbeddingModel`。

向量维度是数据库表结构的一部分：Flyway 用配置占位符创建 `vector(1024)`，启动时用真实请求校验返回维度。服务单次最多 10 条，`BatchingStrategy` 必须同时限制 token 和条数。

## 密钥与环境 {#secrets}

- 根目录 `.env.example` 只有占位符；真实 `.env` 不提交；
- PostgreSQL、主链路模型、Embedding 的凭证互相独立；
- `ConfigLoader` 负责占位符解析和必填校验；
- 日志、工具结果和审计字段都不能打印密钥。

## 辅助调用与评估 {#auxiliary}

生成 `AGENT.md` 草稿和评估器可以使用 `ChatClient`，但不传 tools，并关闭 `ToolCallingAdvisor` 自动注册。若配置独立的 DeepSeek Provider，可让评估器使用它，避免主链路模型“自己评自己”。

## 设计依据 {#sources}

- [技术方案第 3 章（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [技术方案第 8.8 节（中文）](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)

---
title: Models and configuration
lang: en-US
description: Explicit Providers, reasoning-model parameters, and independent Embedding configuration in AgentOS.
---

# Models and configuration {#models}

AgentOS does not scan `ChatModel` Beans and guess which model they represent. Every Provider has a name that Profiles select explicitly. Embeddings are configured independently from the main path.

## Main-path Provider {#providers}

The test environment uses `primary`, backed by an OpenAI-compatible `gpt-5.6-luna` reasoning-model endpoint. Keep the endpoint, key, and model in environment variables:

```properties
AGENTOS_LLM_BASE_URL=https://your-openai-compatible-endpoint
AGENTOS_LLM_API_KEY=<your-key>
AGENTOS_LLM_MODEL=gpt-5.6-luna
```

Reasoning-model rules:

- do not send `temperature`, because the provider may reject it;
- use `reasoning_effort` to control reasoning intensity;
- use `max_completion_tokens` for the output budget.

The intermediary endpoint must be tested for tool calls, streamed tool calls, usage accounting, rate-limit headers, and `json_schema` structured output. `useProviderStructuredOutput()` stays off until compatibility is confirmed.

## Explicit Provider mapping {#mapping}

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

At startup, `ProviderService` builds one `ChatModel` per configuration entry and stores a `Map<ProviderName, ChatModel>`. Multiple OpenAI-compatible services can coexist, but each must have a distinct Provider name.

## Independent Embeddings {#embedding}

The test environment uses Alibaba Cloud Model Studio `text-embedding-v4`:

```properties
AGENTOS_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
AGENTOS_EMBEDDING_API_KEY=<dashscope-key>
AGENTOS_EMBEDDING_MODEL=text-embedding-v4
AGENTOS_EMBEDDING_DIMENSIONS=1024
```

Do not include `/v1` in `base-url`; the Spring AI OpenAI module calls `/v1/embeddings`. The model Starter’s Embedding auto-configuration is disabled, and the application creates one programmatic `OpenAiEmbeddingModel`.

Vector dimensions are part of the database schema. Flyway uses a configuration placeholder to create `vector(1024)`, and startup validates the returned dimension with a real request. Model Studio accepts at most 10 inputs per request, so `BatchingStrategy` must limit both tokens and item count.

## Secrets and environments {#secrets}

- The root `.env.example` contains placeholders; the real `.env` is never committed;
- PostgreSQL, the main model, and Embeddings have separate credentials;
- `ConfigLoader` resolves placeholders and validates required values;
- logs, tool results, and audit fields must not expose secrets.

## Auxiliary calls and evaluation {#auxiliary}

Generating an `AGENT.md` draft and running evaluators may use `ChatClient`, but they pass no tools and disable automatic `ToolCallingAdvisor` registration. If an independent DeepSeek Provider is configured, the evaluator can use it to avoid the main model grading itself.

## Design basis {#sources}

- [Technical solution, Chapter 3 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [Technical solution, Section 8.8 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)

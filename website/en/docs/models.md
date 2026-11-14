---
title: Models and configuration
lang: en-US
description: Explicit Providers, reasoning-model parameters, and independent Embedding configuration in AgentOS.
---

# Models and configuration {#models}

AgentOS does not scan `ChatModel` Beans and guess which model they represent. Every Provider has a name that Profiles select explicitly. A Provider holds only the connection (type, address, credential, timeout); the chat model and generation parameters come from the Profile. Embeddings are configured independently from the main path.

## Main-path Provider {#providers}

The test environment uses `primary`, backed by an OpenAI-compatible `gpt-5.6-luna` reasoning-model endpoint. Keep the endpoint, key, and model in environment variables:

```properties
AGENTOS_LLM_BASE_URL=https://your-openai-compatible-endpoint/v1
AGENTOS_LLM_API_KEY=<your-key>
AGENTOS_LLM_MODEL=gpt-5.6-luna
```

Write `base-url` up to `/v1`; the code does not append it. The reasoning-model rules below are set by the Profile, and any parameter the Profile omits is left out of the request:

- do not send `temperature`, because the provider may reject it;
- use `reasoning_effort` to control reasoning intensity;
- use `max_completion_tokens` for the output budget (the `deepseek` type sends `max_tokens` instead, because DeepSeek ignores the former).

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
      timeout: 120s
      retry-min-remaining: 15s
      retry-max-wait: 120s
    deepseek:
      type: deepseek
      base-url: ${AGENTOS_DEEPSEEK_BASE_URL:https://api.deepseek.com}
      api-key: ${AGENTOS_DEEPSEEK_API_KEY:}
      timeout: 60s
```

At startup, `ProviderService` builds one connection per configuration entry and keys it by name; it registers no `ChatModel` Bean. Multiple OpenAI-compatible services can coexist, but each must have a distinct Provider name.

Two connection types are supported today: `openai` (OpenAI-compatible) and `deepseek`. They share one implementation and differ only in the output-limit field. Any other type, such as `anthropic` or `ollama`, fails startup. A missing key on the default connection also fails startup; on any other connection it only marks that connection unavailable. An unknown provider name, an unavailable connection, or a Profile without a model all fail before any network request is sent.

## Retries and deadlines {#retry}

Only 429, 500 / 502 / 503 / 504, and transient connection errors are retried, with at most one extra attempt per call. Authentication, invalid-request, and unknown-model errors are never retried. If the time left after waiting out `Retry-After` is below `retry-min-remaining`, there is no retry, and the retry request's timeout is the smaller of the connection timeout and the time left. A streamed call is not retried once it has delivered text to the caller. Every request actually sent produces one call record; for now it goes to the structured log, and persisting it to the database belongs to US-2.

## Image input {#images}

A user message can carry images, inline or by URL, and both connection types behave the same way. Audio, video, and other non-image media fail before any request is sent. The call record keeps only the image count, never the image data or URL.

## Independent Embeddings {#embedding}

The test environment uses Alibaba Cloud Model Studio `text-embedding-v4`:

```properties
AGENTOS_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AGENTOS_EMBEDDING_API_KEY=<dashscope-key>
AGENTOS_EMBEDDING_MODEL=text-embedding-v4
AGENTOS_EMBEDDING_DIMENSIONS=1024
```

Write `base-url` up to `/v1`; the final request goes to `<base-url>/embeddings`. The model Starter’s Embedding auto-configuration is disabled, and the application creates one programmatic `OpenAiEmbeddingModel`. When Embeddings are disabled or the key is empty, none is created and calls fail with a clear “not enabled” error.

Vector dimensions are part of the database schema. Flyway uses a configuration placeholder to create `vector(1024)`. Once the application is ready, one real request validates the returned dimension in the background without blocking startup. A mismatch marks Embeddings as misconfigured and rejects calls; an unreachable or unpaid service is re-validated in the background with backoff. Set `startup-check: sync` to refuse startup on a dimension mismatch. Model Studio accepts at most 10 inputs per request, so `EmbeddingService` batches by item count itself.

## Secrets and environments {#secrets}

- The root `.env.example` contains placeholders; the real `.env` is never committed;
- PostgreSQL, the main model, and Embeddings have separate credentials;
- resolving placeholders and validating required values is meant to be `ConfigLoader`’s job; in US-1 Spring placeholders plus the Provider auto-configuration do it, and US-5 moves it over;
- logs, tool results, and audit fields must not expose secrets.

## Auxiliary calls and evaluation {#auxiliary}

Generating an `AGENT.md` draft and running evaluators may use `ChatClient`, but they pass no tools and disable automatic `ToolCallingAdvisor` registration. If an independent DeepSeek Provider is configured, the evaluator can use it to avoid the main model grading itself.

## Design basis {#sources}

- [Technical solution, Chapter 3 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)
- [Technical solution, Section 8.8 (Chinese)](https://github.com/wangkaiqistart/agent-os/blob/main/docs/TechnicalSolution.md)

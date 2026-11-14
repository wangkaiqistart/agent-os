# Quickstart: 验证 US-1 对接 LLM

**Plan**: [plan.md](plan.md) | 行为契约见 [contracts/provider-service.md](contracts/provider-service.md)，配置见 [contracts/configuration.md](contracts/configuration.md)

## 前置条件

- JDK 21、Maven 3.9+，在仓库根目录执行命令。
- 离线验证不需要任何 key，也不需要 Docker 和数据库。
- 真实模型冒烟需要仓库根目录的 `.env`，并且 base-url 写到 `/v1`（research R2）：

  ```bash
  grep -E "^AGENTOS_(LLM|EMBEDDING)_BASE_URL=" .env    # 两个值都应以 /v1 结尾
  ```

## 1. 离线验证（默认构建）

```bash
mvn -B verify
```

预期：全部模块通过；新增的 provider 测试全部用本地 HTTP 替身，不出网；`llm` 标签的测试被跳过。

| 场景 | 测试 | 对应 |
|------|------|------|
| 两个同协议连接按名称路由，不串台 | `ProviderRoutingTest` | US1-1、SC-001 |
| 未知 provider、连接不可用、缺模型：0 次请求 | `ProviderRoutingTest` | US1-4、SC-002 |
| 请求体：有推理强度和输出上限、无温度；`openai` 发 `max_completion_tokens`、`deepseek` 发 `max_tokens`；不传工具时无 `tools`；`toolContext` 不出现 | `RequestMappingTest` | FR-008 ~ FR-011、SC-008、SC-015 |
| 工具调用原样返回，工具执行 0 次 | `RequestMappingTest`、`StreamingTest` | US1-3、SC-009 |
| 最终请求路径为 `/v1/chat/completions`、`/v1/embeddings` | `RequestMappingTest`、`EmbeddingServiceTest` | FR-027 |
| 429 + `Retry-After: 1` 后成功：2 次请求、间隔 ≥ 1 秒 | `RetryPolicyTest` | US2-1 |
| 503 两次：2 次请求后 `PROVIDER_UNAVAILABLE`；401 / 400：1 次请求 | `RetryPolicyTest` | US2-2、US2-3、SC-003 |
| 预计等待后剩余时间少于 `retry-min-remaining` 时不等待不重试；Retry-After 超过剩余预算时立即失败；预算足够时重试且超时缩短到剩余时间；120 秒连接超时、30 秒剩余时在约 30 秒失败（测试按比例缩短时间） | `DeadlineTest` | US2-4 ~ US2-6、SC-004、SC-021 |
| 429 后成功产生 2 条记录；缺用量与限流头时为空不为 0；缓存 token 原样记录 | `CallRecordTest` | US3-1 ~ US3-3、SC-005、SC-006 |
| 输出超过上限时记录 `outputCapHonored=false` 并告警 | `CallRecordTest` | SC-016 |
| 日志中无 key、Prompt、工具参数、图片数据与网址 | `RedactionTest` | US3-4、SC-007、SC-019 |
| 文本增量按顺序到达；拆成 3 段的工具参数被拼完整；交出增量后断流不重试；只有 1 条记录 | `StreamingTest` | US4-1 ~ US4-4 |
| 内嵌图片与网址按原顺序发出，两种连接一致；音频、视频外呼前失败 | `ImageInputTest` | US6-1 ~ US6-4、SC-017、SC-018 |
| 默认连接 key 为空启动失败并指出变量名；可选连接为空只标记不可用；不支持的类型启动失败；容器里没有 `ChatModel` / `EmbeddingModel` Bean | `StartupValidationTest` | US1-6、SC-011 |
| 11 条分 2 批、顺序一致；空列表不发请求；未启用时明确报错且无实例 | `EmbeddingServiceTest` | US5-1 ~ US5-3、SC-013、SC-014 |
| `async`：替身延迟响应时启动不等待；校验中的调用等待结果；维度不一致标记 `MISCONFIGURED` 并拒绝调用；不可达、欠费后台退避重新校验并恢复，401 不重新校验 | `EmbeddingStartupTest` | US5-4、US5-5、US5-7、SC-012、SC-020 |
| `sync`：维度不一致拒绝启动 | `EmbeddingStartupTest` | US5-4、SC-012 |
| 校验通过后某次调用返回维度不一致：调用失败、不返回向量、转为 `MISCONFIGURED` | `EmbeddingServiceTest` | US5-8、SC-012 |
| `retry-min-remaining` 非正数或大于 `timeout`、`startup-check` 取值非法时启动失败 | `StartupValidationTest` | FR-003、FR-029 |
| 只有 provider 包依赖 Spring AI OpenAI；`@Bean` 不返回 `ChatModel` | `ArchitectureRulesTest` | FR-004、FR-030 |

## 2. 真实模型冒烟（单独开启）

```bash
set -a; source .env; set +a
mvn -B verify -Pllm -pl AgentOS-provider -am
```

预期（缺少对应 key 的测试自动跳过）：

| 测试 | 检查 |
|------|------|
| `PrimarySmokeTest` | 同步和流式都返回非空文本；记录里实际模型、token 用量有值；纯红色图片回答"红色"。输出上限只检查请求字段，不检查结束原因（中转接口不遵守，research 与 spec SC-015） |
| `DeepSeekSmokeTest` | 同步和流式都返回结果；设置很小的输出上限时结束原因为 `LENGTH`；纯红色图片回答"红色" |
| `EmbeddingSmokeTest` | 返回 1024 维向量；后台校验完成后状态为 READY |

## 3. 人工确认

- `AgentOS-provider/pom.xml` 只剩 `spring-ai-openai` 一个模型依赖。
- 代码里有指向 US-2 的 TODO：`LoggingLlmCallRecorder` 将由写 `llm_calls` 的实现替换。
- `.AgentOS/logs/agentos.log` 里的 `llm_call` 事件是一行 JSON，字段与 [data-model.md](data-model.md) 的 `LlmCallRecord` 一致。

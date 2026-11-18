# Contract: Tool Registry and Authorization

## Registration

每个注册工具必须提供：

- 唯一 `name`；
- 非空、面向模型的 `description`；
- 可解析的 JSON `inputSchema`；
- 保留的工具元数据，包含 `returnDirect`；
- 统一来源标识。

目录建立时必须拒绝重名、缺少名称、描述或输入约束。目录不扫描任意应用组件，也不将 Agent 或 Skill 目录注册为工具。

## Agent filtering

- Agent 配置的 `tools` 字段是有序名称列表。
- 默认空列表；空列表合法且模型请求不携带工具定义。
- 非空列表必须全部命中目录；未知名称在模型调用前报告配置来源和工具名称。
- 返回集合严格按 Agent 声明顺序，不能把全局目录全部发给模型。
- 合法 Agent 只获得经过 `GovernedToolCallback` 包装的回调。

## Runtime fallback

运行中解析到 Agent 未授权或目录不存在的名称时：

1. 不解析同名 Bean、Function 或其他组件。
2. 创建只描述“未授权工具”的安全拒绝回调。
3. 通过同一治理和审计路径返回工具错误。
4. 不执行底层动作；模型可在本轮继续。

## Verification

至少覆盖：

- 内置和内部扩展工具统一注册；
- 精确子集和声明顺序；
- 空工具集合；
- 未知工具配置在模型外呼前失败；
- 重名和缺少定义；
- 未授权运行时调用零底层执行；
- 请求级 callbacks 优先于安全 fallback；
- 启动只装配一个 Manager、resolver 和异常处理器。

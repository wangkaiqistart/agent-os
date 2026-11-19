# 005 真实外部依赖结果

2026-11-19，Asia/Shanghai。仅检查显式进程环境变量是否存在，未读取.env或个人凭证；默认verify不外呼。

| 依赖 | 状态 | 证据与原因 |
|---|---|---|
| 主链路真实模型 | NOT_RUN | AGENTOS_LLM_API_KEY/BASE_URL/MODEL均未注入；显式llm运行条件跳过 |
| 三个Demo官方评估器 | NOT_RUN | 同上；已交付实际工具资料、每项3次isPass、至少2次通过的测试 |
| Brave | NOT_RUN | AGENTOS_EXTERNAL_TOOL_TESTS和AGENTOS_EXTERNAL_BRAVE_API_KEY未注入 |
| 外部MCP STDIO | NOT_RUN | 总开关与AGENTOS_EXTERNAL_MCP_STDIO_CONFIG未注入 |
| 外部MCP SSE | NOT_RUN | 总开关与AGENTOS_EXTERNAL_MCP_SSE_CONFIG未注入 |
| 外部MCP Streamable HTTP | NOT_RUN | 总开关与AGENTOS_EXTERNAL_MCP_HTTP_CONFIG未注入 |

显式测试入口分别为`-Pllm -Dtest=DemoEvaluationIntegrationTest`与`-Pexternal-tools -Dtest=ExternalToolCompatibilityTest`，
均加`-pl AgentOS-boot -am -Dsurefire.failIfNoSpecifiedTests=false test`。日志索引：
`/tmp/agentos-005-llm.log`、`/tmp/agentos-005-external.log`。缺依赖跳过不代表外部协议或模型评估通过。
本地HTTP/MCP/搜索协议替身的PASS只证明隔离环境行为。

# 005 三个汇总 Demo

2026-11-19，Asia/Shanghai。`ToolSummaryDemoIntegrationTest`通过真实Manager、隔离PostgreSQL与本地模型协议。

| Demo | 离线动作链 | 离线状态 | 真实模型/官方评估 |
|---|---|---|---|
| 天气 | http_get读取本地北京22℃资料，回填后返回当前入口 | PASS | NOT_RUN，FactCheckingEvaluator未运行 |
| 科技 | read_file读取Skill，再web_search取本地Brave协议资料 | PASS | NOT_RUN，RelevancyEvaluator未运行 |
| GitHub | repository_info业务Java插件返回Java/3个问题/2个提交 | PASS | NOT_RUN，FactCheckingEvaluator未运行 |

重放：`python3 specs/005-expand-tool-capabilities/fixtures/demo_summaries.py --output /tmp/agentos-005-demos`。
每个Demo记录可见定义、原callId、成功审计、工具资料、事件ID、下一次模型回填与最终回答；安全证据：
`fixtures/evidence/demo-summary-results.json`。不依赖Memory、Notify或调度，仓库查询不以通用shell代替业务插件。

真实模型测试见`llm/DemoEvaluationIntegrationTest.java`；EvaluationRequest只取实际审计工具返回和最终回答，
天气/仓库FactCheckingEvaluator，科技RelevancyEvaluator，每项3次isPass至少2次通过。
有显式deepseek凭证和模型时优先独立评估，否则primary并记录sameModel自评限制；ChatClient不传tools。
本次缺AGENTOS_LLM_API_KEY/BASE_URL/MODEL，三次isPass均无结果、Provider未执行，不计为PASS。

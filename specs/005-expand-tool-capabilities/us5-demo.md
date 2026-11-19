# US5：HTTP POST 阶段验收

日期：2026-11-19（Asia/Shanghai）。仅对隔离本地服务发送请求。

```bash
mvn -B -ntp -pl AgentOS-boot -am -Dtest=HttpPostContractTest,HttpPostDeadlineTest,HttpPostAcceptanceIntegrationTest,HttpToolsTest,HttpIoScopeContractTest,ToolAutoConfigurationTest,ArchitectureRulesTest,ToolSnapshotBeforeTurnIntegrationTest,ReActDeadlineIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

`http_post(url, body, content_type?)` 默认 application/json，另支持 text/plain。请求/响应独立1MiB上限；UTF-8字节上限先于结果字符裁剪。公开schema使用content_type，薄适配将它映射到符合Java规范的contentType，仍委托官方方法callback；未知参数由原治理校验拒绝。

证据日志：`/tmp/agentos-005-us5-red.log`、`/tmp/agentos-005-us5-boot-red.log` 为测试先行行为红灯；`/tmp/agentos-005-us5-green.log` 为修复后定向绿灯；`/tmp/agentos-005-us5-verify.log` 为阶段质量门：00:57:18完成，43项测试0失败/错误/跳过，10模块质量门全部通过。

覆盖及实际观测：

- 文本、JSON、默认类型各一次提交，UTF-8正文与Content-Type一致；200正文的success=false保留，completion=CONFIRMED仅表示传输完成。
- 无效JSON、类型、URL/userinfo、未授权域名、请求超限均0请求。收紧至32字节时32字节中文正文通过，33字节拒绝；长响应结果保持合法JSON与truncated/completion字段。
- 非2xx、超大响应不回显原正文；302目标0请求。收到请求后断线、慢响应头、持续正文均原目标恰为1，不重试。持续正文与取消在服务兜底关闭之前观测到对端断开。
- PostgreSQL/真实Manager闭环验证允许、拒绝、错误参数、begin未确认、complete未确认、取消、租约失效：实际提交数分别1/0/0/0/1/0/0；原callId/source=builtin保留，POST恒非幂等。已发送故障保守提示可能生效；审计未知不重放。
- 部署秘密哨兵放入响应，模型下一次请求中已脱敏。GET原跳转、响应限制和deadline，以及既有真实审计/快照回归继续通过。

实现不跟随跳转，不启用JDK对所有方法重试选项；发现该选项开启时提交安全拒绝。底层非幂等方法的无重发语义以真实断线后的请求计数验证，不能只依赖policy=false。

这是筛选阶段验证，不能代替最终完整005质量门。真实外部POST：NOT_RUN。

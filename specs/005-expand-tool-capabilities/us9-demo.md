# US9 工具目录查询验证

2026-11-19 02:12:55 +08:00，定向verify PASS，36.538s，10个reactor质量门通过。

独立NONE/Servlet目录模式没有DataSource、ProviderService、AgentService、ToolCallingManager、SessionManager或ToolInvocationRecorder。
不构造假审计器。查询仅从原子目录取安全投影，MCP后台维护，不等待坏来源。
CLI/HTTP注入同一冻结快照，按tool-query.openapi.yaml核对字段集合，global和Agent视图JSON一致；已配置但未发现的来源有状态行。
认证前读取次数0，正确认证后两入口各读取一次，无业务链可创建模型调用/工具动作/会话或轮次。

认证测试涵盖回环与显式远程、正确/错误/缺失/未配置token，重复头、非法头及URL token；缺错固定401，未配入口不装配。
真实Servlet分别在127.0.0.1与显式0.0.0.0配置下运行，测试连接仍限制本机；不代表已验证外网TLS代理。
响应no-store，无通配CORS，目录不包含schema/callback/连接地址/命令/env/凭证；Agent未知/非法为安全400/404。

```bash
mvn -B -ntp -pl AgentOS-boot -am -Dtest=ToolCatalogQueryTest,ToolListCommandTest,ToolCatalogAuthenticationTest,ToolCatalogModeIntegrationTest,ToolCatalogQueryAcceptanceIntegrationTest,ArchitectureRulesTest,ToolCapabilitiesPrivacyIntegrationTest,ToolSummaryDemoIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false verify
java -jar AgentOS-boot/target/AgentOS.jar tool list --workspace .AgentOS --json
java -jar AgentOS-boot/target/AgentOS.jar --config /path/catalog.yaml tool serve --workspace .AgentOS
```

catalog.yaml中配置agentos.tools.query.enabled=true、access-token=${AGENTOS_TOOL_QUERY_TOKEN}，默认127.0.0.1:8080；
本地进程环境注入令牌，不放URL/命令参数/日志；远程由部署者提供TLS/加密反向代理。
standalone CLI没有boot launcher时固定非零，不伪造目录结果；chat保持NONE模式。

静态检查发现并修复Binder结果可空及Filter响应写法：显式null分支，JSON mapper直接序列化固定ApiResponse至输出流。
未放宽SpotBugs/Checkstyle/ArchUnit。安全日志索引：`/tmp/agentos-005-us9-verify.log`。

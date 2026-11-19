# US4：MCP 真实本地协议验收

日期：2026-11-19（Asia/Shanghai）。默认验证只连接隔离的本地 Python 标准库协议替身，不读取 `.env`。

## 接入与重放

在工作区 `mcp_servers.yaml` 的 `servers` 中配置唯一 server-id 和三种 transport 之一；字段和凭证环境引用见 `contracts/configuration.md`。STDIO 的真实 executable/cwd 还需配置 shell 白名单，HTTP 需配置域名白名单。Agent 同时声明 `mcp_servers: [news]` 与 `tools: [mcp_news__echo]`，公开名不会随远端自报名称变化。

```bash
mvn -B -ntp -pl AgentOS-boot -am '-Dtest=Mcp*Test,AgentMcpAuthorizationTest,McpToolAcceptanceIntegrationTest,AgentLoaderTest,ToolRegistryTest,ArchitectureRulesTest' -Dsurefire.failIfNoSpecifiedTests=false verify
mvn -B -ntp -pl AgentOS-tool -am -Dtest=McpTransportContractTest,McpContextIsolationTest,McpFailureBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

## 证据

- `/tmp/agentos-005-us4-{transport-red,red,boot-red,profile-red}.log` 保留初始行为红灯；配置/传输/生命周期骨架曾编译后按预期失败，不把编译失败算行为证据。
- `/tmp/agentos-005-us4-verify.log`：00:44:57 完成，44 项测试，0 失败/错误/跳过，10 个模块质量门通过。覆盖 core 的6项、tool 的23项和 boot 的15项；此为筛选阶段验证。
- `/tmp/agentos-005-us4-final.log`：00:45:45 完成，补验跨 scheme/host/有效 port 的恶意 SSE endpoint、错误日志隔离，6 项测试和 core/tool 静态质量门通过。跨源目标收到请求及凭证均为0；两个默认端口同源比较通过。
- `/tmp/agentos-005-us4-cancel.log`：00:47:25 完成，7项测试0失败/错误/跳过，补验实际调用后取消与单工具短 deadline，确认仅原来源失效、另一来源仍可调用、关闭后不再重连。
- 三 transport 均由官方客户端真实 initialize/list/call，调用计数恰为1；STDIO/SSE 在夹具兜底清理之前观察到客户端关闭。Streamable HTTP 为无会话替身，无伪造 DELETE 关闭计数。
- PostgreSQL 测试对每 transport 执行允许、未授权、begin失败、complete未确认四种路径；实际远程动作分别为1/0/0/1，保留原 callId 与 `mcp:<id>` 来源。begin失败没有审计行，complete未确认保留原行和一次尝试，不重放。
- 旧代次回调失效后调用计数不增加；新轮次隐藏不可用来源，恢复只能重新绑定新代次。新工具不自动授权，恢复缺失声明工具在建轮次前报错。
- 后台启动调度返回小于1秒；慢来源不拖累健康来源。初始化及全分页共用10秒，累计1001个工具拒绝发布，慢分页不能逐页续时。
- 1 MiB 以上响应、断线、跳转均安全失败，业务调用只有一次，重定向目标请求为0。STDIO 宿主 HOME/PATH 不继承，显式业务变量可见；stderr只排空。未知内部上下文哨兵未进入远端业务参数或 metadata，远端 isError 内容经脱敏、限长后只出现在返回模型的 REMOTE_ERROR 中，URL 令牌被遮蔽，未进入原始日志。

## 生命周期与限制

连接仅在未来轮次恢复，退避1/2/4秒至300秒；失效实例不会复活。调用取消使用原 scope 与共享1秒清理窗口，不更改共享客户端 requestTimeout，不把恢复当重试。远端动作可能已经生效；关闭连接不能撤销远端业务副作用。

STDIO adapter 只处理受控宿主进程/有界管道，继续使用官方 mapper、协议会话与 callback provider。初始化进程成功移交来源所有权，不随初始化 scope 正常关闭。文件与进程仍遵守可信单机部署边界。

外部 MCP 联调：**NOT_RUN**。完整005默认质量门、后续工具及真实模型评估尚未完成，不能据本阶段报告声称全部005验收通过。
